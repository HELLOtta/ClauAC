package io.github.hellotta.clauac.simulation.player;

import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// - Port of the parts of the client-only LocalPlayer and AbstractClientPlayer that decide how the local player moves, -
// - on foot and on a vehicle. Everything else is inherited unchanged from the vanilla Player, LivingEntity and Entity -
// - classes. Left out on purpose, because they only render, play or show something: view bobbing, walked distance, -
// - first person hands, ambient sounds, water vision, the nausea and portal spinning effect, the tutorial, the riding -
// - sounds and every screen -
public final class SandboxPlayer extends Player {

    // - Player.attack weighs the attack strength half a tick ahead -
    private static final float ATTACK_STRENGTH_PARTIAL_TICK = 0.5F;
    // - Player.attack only makes a knockback attack, the one that slows the attacker down, above this strength -
    private static final float FULL_ATTACK_STRENGTH = 0.9F;
    // - The knockback Player.attack adds for a knockback attack -
    private static final float KNOCKBACK_ATTACK_BONUS = 0.5F;

    private final ClientContext client;
    private final CachedPlayerInfo playerInfo;
    public SandboxInput input = new SandboxInput();
    private PermissionSet permissions = PermissionSet.NO_PERMISSIONS;
    private Input reportedKeys = Input.EMPTY;
    private boolean crouching;
    // - LocalPlayer.handsBusy -
    private boolean handsBusy;
    private boolean flashOnSetHealth;
    private boolean startedUsingItem;
    // - The answer isInWaterOrRain gives while an alternative tries the other outcome of rain that the client's -
    // - lagging sky light may have given (see withWaterOrRain); null otherwise -
    private @Nullable Boolean forcedWaterOrRain;
    // - LocalPlayer's charge of the jump of a vehicle that can jump (horses, camels) -
    private int jumpRidingTicks;
    private float jumpRidingScale;
    private @Nullable InteractionHand usingItemHand;
    // - The other values the client's attackStrengthTicker may have. A hotbar switch the client reports at the start -
    // - of a tick may already have happened during the previous tick's key handling (see PlayConnection); the -
    // - client's player then reset the ticker one tick before this one did. Values that became equal to this -
    // - player's ticker are dropped -
    private final List<AlternativeAttackStrength> alternativeAttackStrengths = new ArrayList<>();
    private boolean tickingPlayer;
    private boolean mainHandChangeResetDuringTick;
    // - Mirrors of Player.lastItemInMainHand, the item Player.tick compares the main hand with: its value before -
    // - the player's last tick, and its value now -
    private ItemStack mainHandItemBeforeLastTick = ItemStack.EMPTY;
    private ItemStack mainHandItemAfterLastTick = ItemStack.EMPTY;
    // - What the last Player.attack did to the player itself, recorded while it ran (see attack) -
    private @Nullable AttackRecord lastAttack;
    private boolean attacking;

    public SandboxPlayer(Level level, GameProfile profile, ClientContext client) {
        super(level, profile);
        this.client = client;
        this.playerInfo = new CachedPlayerInfo(client, profile.id());
    }

    // - The keys of the ServerboundPlayerInputPacket for the tick about to be simulated -
    public void setReportedKeys(Input reportedKeys) {
        this.reportedKeys = reportedKeys;
    }

    // - The client gives its player a new KeyboardInput when it joins a level, which starts without keys -
    public void replaceInput() {
        this.input = new SandboxInput();
    }

    // - LocalPlayer.setExperienceValues, from ClientboundSetExperiencePacket. The levels decide what an anvil lets -
    // - the player take -
    public void setExperienceValues(float experienceProgress, int totalExperience, int experienceLevel) {
        this.experienceProgress = experienceProgress;
        this.totalExperience = totalExperience;
        this.experienceLevel = experienceLevel;
    }

    // - LocalPlayer.clientSideCloseContainer: the client returns to its own inventory menu -
    public void clientSideCloseContainer() {
        super.closeContainer();
    }

    // - LocalPlayer.raycastHitResult: what the crosshair points at, which Minecraft.pick computes at the start of -
    // - every tick with a partial tick of 1 -
    public HitResult raycastHitResult(float partialTicks, Entity cameraEntity) {
        return this.raycastHitResult(partialTicks, cameraEntity, this.getActiveItem());
    }

    // - The same for a player holding out this item instead (LivingEntity.getActiveItem): an item with an attack -
    // - range picks along that range first -
    public HitResult raycastHitResult(float partialTicks, Entity cameraEntity, ItemStack activeItem) {
        AttackRange itemAttackRange = activeItem.get(DataComponents.ATTACK_RANGE);
        double blockInteractionRange = this.blockInteractionRange();
        HitResult hitResult = null;
        if (itemAttackRange != null) {
            hitResult = itemAttackRange.getClosesetHit(cameraEntity, partialTicks, EntitySelector.CAN_BE_PICKED);
            if (hitResult instanceof BlockHitResult) {
                hitResult = filterHitResult(hitResult, cameraEntity.getEyePosition(partialTicks), blockInteractionRange);
            }
        }

        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
            double entityInteractionRange = this.entityInteractionRange();
            hitResult = pick(cameraEntity, blockInteractionRange, entityInteractionRange, partialTicks);
        }

        return hitResult;
    }

    private static HitResult pick(Entity cameraEntity, double blockInteractionRange, double entityInteractionRange, float partialTicks) {
        double maxDistance = Math.max(blockInteractionRange, entityInteractionRange);
        double maxDistanceSq = Mth.square(maxDistance);
        Vec3 from = cameraEntity.getEyePosition(partialTicks);
        HitResult blockHitResult = cameraEntity.pick(maxDistance, partialTicks, false);
        double blockDistanceSq = blockHitResult.getLocation().distanceToSqr(from);
        if (blockHitResult.getType() != HitResult.Type.MISS) {
            maxDistanceSq = blockDistanceSq;
            maxDistance = Math.sqrt(maxDistanceSq);
        }

        Vec3 direction = cameraEntity.getViewVector(partialTicks);
        Vec3 to = from.add(direction.x * maxDistance, direction.y * maxDistance, direction.z * maxDistance);
        AABB box = cameraEntity.getBoundingBox().expandTowards(direction.scale(maxDistance)).inflate(1.0, 1.0, 1.0);
        EntityHitResult entityHitResult = ProjectileUtil.getEntityHitResult(cameraEntity, from, to, box, EntitySelector.CAN_BE_PICKED, maxDistanceSq);
        return entityHitResult != null && entityHitResult.getLocation().distanceToSqr(from) < blockDistanceSq
                ? filterHitResult(entityHitResult, from, entityInteractionRange)
                : filterHitResult(blockHitResult, from, blockInteractionRange);
    }

    private static HitResult filterHitResult(HitResult hitResult, Vec3 from, double maxRange) {
        Vec3 hitLocation = hitResult.getLocation();
        if (!hitLocation.closerThan(from, maxRange)) {
            Vec3 location = hitResult.getLocation();
            Direction direction = Direction.getApproximateNearest(location.x - from.x, location.y - from.y, location.z - from.z);
            return BlockHitResult.miss(location, direction, BlockPos.containing(location));
        }
        return hitResult;
    }

    @Override
    public boolean isLocalPlayer() {
        return true;
    }

    @Override
    public @Nullable GameType gameMode() {
        return this.playerInfo.gameMode();
    }

    // - Operator levels decide whether the player may break or use game master blocks -
    @Override
    public PermissionSet permissions() {
        return this.permissions;
    }

    @Override
    public void handleEntityEvent(byte id) {
        switch (id) {
            case EntityEvent.PERMISSION_LEVEL_ALL -> this.permissions = PermissionSet.NO_PERMISSIONS;
            case EntityEvent.PERMISSION_LEVEL_MODERATORS -> this.permissions = LevelBasedPermissionSet.MODERATOR;
            case EntityEvent.PERMISSION_LEVEL_GAMEMASTERS -> this.permissions = LevelBasedPermissionSet.GAMEMASTER;
            case EntityEvent.PERMISSION_LEVEL_ADMINS -> this.permissions = LevelBasedPermissionSet.ADMIN;
            case EntityEvent.PERMISSION_LEVEL_OWNERS -> this.permissions = LevelBasedPermissionSet.OWNER;
            default -> super.handleEntityEvent(id);
        }
    }

    // - LocalPlayer.rideTick hands the keys to a boat the player steers, whose paddles keep the player's hands busy: -
    // - Minecraft.startAttack and startUseItem then do nothing -
    @Override
    public void rideTick() {
        super.rideTick();
        this.handsBusy = false;
        if (this.getControlledVehicle() instanceof AbstractBoat boat) {
            boat.setInput(this.input.keyPresses.left(), this.input.keyPresses.right(), this.input.keyPresses.forward(), this.input.keyPresses.backward());
            this.handsBusy = this.handsBusy
                    | (this.input.keyPresses.left() || this.input.keyPresses.right() || this.input.keyPresses.forward() || this.input.keyPresses.backward());
        }
    }

    // - LocalPlayer.removeVehicle -
    @Override
    public void removeVehicle() {
        super.removeVehicle();
        this.handsBusy = false;
    }

    // - LocalPlayer.isHandsBusy -
    public boolean isHandsBusy() {
        return this.handsBusy;
    }

    @Override
    public void tick() {
        if (this.client.hasClientLoaded()) {
            this.tickingPlayer = true;
            this.mainHandChangeResetDuringTick = false;
            this.mainHandItemBeforeLastTick = this.mainHandItemAfterLastTick;
            try {
                super.tick();
            } finally {
                this.tickingPlayer = false;
            }
            this.mainHandItemAfterLastTick = this.getMainHandItem().copy();
            this.advanceAlternativeAttackStrengths();
            this.client.onPlayerTicked();
        }
    }

    // - Called right after the hotbar switch was applied. The client's player may already have held the new main -
    // - hand item during its last tick; Player.tick then reset the ticker to zero in that tick if the item differed -
    // - from the one before, a reset this player only does in its coming tick -
    public void considerEarlierHotbarSwitch() {
        if (!ItemStack.isSameItem(this.mainHandItemBeforeLastTick, this.getMainHandItem())) {
            this.alternativeAttackStrengths.add(new AlternativeAttackStrength());
        }
    }

    // - Player.attack decides at its start whether the attack is a knockback attack, one made sprinting at full -
    // - strength. The decision is recorded for this player's ticker and for every alternative one before -
    // - Player.onAttack resets them all -
    @Override
    public void attack(Entity entity) {
        boolean sprinting = this.isSprinting();
        boolean knockbackAttack = sprinting && this.isFullStrengthAttack(this.attackStrengthTicker);
        List<Integer> tickersWithOtherKnockbackAttack = new ArrayList<>();
        for (AlternativeAttackStrength alternative : this.alternativeAttackStrengths) {
            if ((sprinting && this.isFullStrengthAttack(alternative.ticker)) != knockbackAttack) {
                tickersWithOtherKnockbackAttack.add(alternative.ticker);
            }
        }
        this.lastAttack = new AttackRecord(knockbackAttack, List.copyOf(tickersWithOtherKnockbackAttack));
        this.attacking = true;
        try {
            super.attack(entity);
        } finally {
            this.attacking = false;
        }
    }

    // - Player.attack adds the attack's knockback to this one once the attack hurt its target. On the client it only -
    // - comes from the attack knockback attribute: the enchantments count on the server (LivingEntity.getKnockback) -
    @Override
    protected float getKnockback(Entity target, DamageSource damageSource) {
        float knockback = super.getKnockback(target, damageSource);
        if (this.attacking && this.lastAttack != null) {
            this.lastAttack.baseKnockback = knockback;
        }
        return knockback;
    }

    // - Player.attack calls this exactly when the attack hurt its target; a positive knockback slows the attacker -
    @Override
    public void causeExtraKnockback(Entity entity, float knockbackAmount, Vec3 oldMovement, DamageSource damageSource, float damage, boolean comesFromEffect) {
        if (this.attacking && this.lastAttack != null) {
            this.lastAttack.hurtTarget = true;
            this.lastAttack.knockback = knockbackAmount;
        }
        super.causeExtraKnockback(entity, knockbackAmount, oldMovement, damageSource, damage, comesFromEffect);
    }

    // - An attack strength ticker the client may have had instead (see alternativeAttackStrengths) under which the -
    // - last attack would have slowed the player down when this player's did not, or not when it did. Whether an -
    // - attack hurts does not depend on its strength on the client, since Entity.hurtClient takes no damage -
    public OptionalInt tickerForOtherAttackSlowdown() {
        AttackRecord attack = this.lastAttack;
        if (attack == null || !attack.hurtTarget || attack.tickersWithOtherKnockbackAttack.isEmpty()) {
            return OptionalInt.empty();
        }
        float otherKnockback = attack.baseKnockback + (attack.knockbackAttack ? 0.0F : KNOCKBACK_ATTACK_BONUS);
        if (otherKnockback > 0.0F == attack.knockback > 0.0F) {
            return OptionalInt.empty();
        }
        return OptionalInt.of(attack.tickersWithOtherKnockbackAttack.getFirst());
    }

    // - Whether an attack now would slow the player down if it hurt its target, under this player's ticker or an -
    // - alternative one (Player.attack and causeExtraKnockback) -
    public boolean attackCouldSlowDown() {
        float baseKnockback = super.getKnockback(this, this.damageSources().playerAttack(this));
        if (baseKnockback > 0.0F) {
            return true;
        }
        if (!this.isSprinting() || baseKnockback + KNOCKBACK_ATTACK_BONUS <= 0.0F) {
            return false;
        }
        if (this.isFullStrengthAttack(this.attackStrengthTicker)) {
            return true;
        }
        for (AlternativeAttackStrength alternative : this.alternativeAttackStrengths) {
            if (this.isFullStrengthAttack(alternative.ticker)) {
                return true;
            }
        }
        return false;
    }

    // - The part of Player.causeExtraKnockback that acts on the attacker, for an attack whose target the sandbox does -
    // - not know: an attack that hurt its target with a positive knockback slows the attacker and stops its sprint -
    public void slowDownAfterAttack() {
        this.setDeltaMovement(this.getDeltaMovement().multiply(0.6, 1.0, 0.6));
        this.setSprinting(false);
    }

    // - Whether the client's attack strength ticker may differ from this player's (see alternativeAttackStrengths) -
    public boolean hasAlternativeAttackStrengths() {
        return !this.alternativeAttackStrengths.isEmpty();
    }

    // - Gives the player the attack strength ticker the client may have had instead, before its attack runs again -
    public void useAttackStrengthTicker(int ticker) {
        this.attackStrengthTicker = ticker;
    }

    // - Player.cannotAttackWithItem as Minecraft.startAttack asks it, under this player's ticker and under every -
    // - alternative one (see alternativeAttackStrengths): true only when the client's player could not attack with -
    // - the item whichever of them it had -
    public boolean cannotAttackWithItemUnderAnyTicker(ItemStack itemStack) {
        int ownTicker = this.attackStrengthTicker;
        try {
            if (!this.cannotAttackWithItem(itemStack, 0)) {
                return false;
            }
            for (AlternativeAttackStrength alternative : this.alternativeAttackStrengths) {
                this.attackStrengthTicker = alternative.ticker;
                if (!this.cannotAttackWithItem(itemStack, 0)) {
                    return false;
                }
            }
            return true;
        } finally {
            this.attackStrengthTicker = ownTicker;
        }
    }

    // - Player.getAttackStrengthScale as Player.attack uses it, for any ticker -
    private boolean isFullStrengthAttack(int attackStrengthTicker) {
        float scale = Mth.clamp((attackStrengthTicker + ATTACK_STRENGTH_PARTIAL_TICK) / this.getCurrentItemAttackStrengthDelay(), 0.0F, 1.0F);
        return scale > FULL_ATTACK_STRENGTH;
    }

    // - Player.tick for the alternative tickers: they count up and reset for a new main hand item, unless the -
    // - client's player already held that item. Afterwards every alternative evolves exactly like this player's -
    // - ticker, so an equal value stays equal -
    private void advanceAlternativeAttackStrengths() {
        for (AlternativeAttackStrength alternative : this.alternativeAttackStrengths) {
            alternative.ticker++;
            if (this.mainHandChangeResetDuringTick && !alternative.heldMainHandItem) {
                alternative.ticker = 0;
            }
            alternative.heldMainHandItem = false;
        }
        Set<Integer> distinctTickers = new HashSet<>();
        distinctTickers.add(this.attackStrengthTicker);
        this.alternativeAttackStrengths.removeIf(alternative -> !distinctTickers.add(alternative.ticker));
    }

    // - During the player's own tick only the main hand check of Player.tick resets the ticker; every other reset -
    // - comes from what the client did, which happened whenever the hotbar switch did -
    @Override
    public void resetAttackStrengthTicker() {
        super.resetAttackStrengthTicker();
        if (this.tickingPlayer) {
            this.mainHandChangeResetDuringTick = true;
        } else {
            this.resetAlternativeAttackStrengths();
        }
    }

    @Override
    public void resetOnlyAttackStrengthTicker() {
        super.resetOnlyAttackStrengthTicker();
        this.resetAlternativeAttackStrengths();
    }

    private void resetAlternativeAttackStrengths() {
        for (AlternativeAttackStrength alternative : this.alternativeAttackStrengths) {
            alternative.ticker = 0;
        }
    }

    @Override
    public boolean isShiftKeyDown() {
        return this.input.keyPresses.shift();
    }

    @Override
    public boolean isCrouching() {
        return this.crouching;
    }

    public boolean isMovingSlowly() {
        return this.isCrouching() || this.isVisuallyCrawling();
    }

    @Override
    protected void applyInput() {
        if (this.isControlledCamera()) {
            Vec2 modifiedInput = this.modifyInput(this.input.getMoveVector());
            this.xxa = modifiedInput.x;
            this.zza = modifiedInput.y;
            this.jumping = this.input.keyPresses.jump();
        } else {
            super.applyInput();
        }
    }

    private Vec2 modifyInput(Vec2 input) {
        if (input.lengthSquared() == 0.0F) {
            return input;
        }

        Vec2 newInput = input.scale(0.98F);
        if (this.isUsingItem() && !this.isPassenger()) {
            newInput = newInput.scale(this.itemUseSpeedMultiplier());
        }

        if (this.isMovingSlowly()) {
            float sneakingMovementFactor = (float) this.getAttributeValue(Attributes.SNEAKING_SPEED);
            newInput = newInput.scale(sneakingMovementFactor);
        }

        return modifyInputSpeedForSquareMovement(newInput);
    }

    private static Vec2 modifyInputSpeedForSquareMovement(Vec2 input) {
        float length = input.length();
        if (length <= 0.0F) {
            return input;
        }

        Vec2 direction = input.scale(1.0F / length);
        float distanceToUnitSquare = distanceToUnitSquare(direction);
        float modifiedLength = Math.min(length * distanceToUnitSquare, 1.0F);
        return direction.scale(modifiedLength);
    }

    private static float distanceToUnitSquare(Vec2 direction) {
        float directionX = Math.abs(direction.x);
        float directionY = Math.abs(direction.y);
        float tan = directionY > directionX ? directionX / directionY : directionY / directionX;
        return Mth.sqrt(1.0F + Mth.square(tan));
    }

    private boolean isControlledCamera() {
        return this.client.isCameraOnPlayer();
    }

    public void resetPos() {
        this.setPose(Pose.STANDING);
        if (this.level() != null) {
            for (double testY = this.getY(); testY > this.level().getMinY() && testY <= this.level().getMaxY(); testY++) {
                this.setPos(this.getX(), testY, this.getZ());
                if (this.level().noCollision(this)) {
                    break;
                }
            }

            this.setDeltaMovement(Vec3.ZERO);
            this.setXRot(0.0F);
        }

        this.setHealth(this.getMaxHealth());
        this.deathTime = 0;
    }

    @Override
    public void aiStep() {
        // - The client skips this while its level loading screen is open, which is only the case before it reported -
        // - itself loaded; tick() does not reach aiStep() before that -
        this.handlePortalTransitionEffect(this.getActivePortalLocalTransition() == Portal.Transition.CONFUSION);
        this.processPortalCooldown();

        boolean wasJumping = this.input.keyPresses.jump();
        boolean wasShiftKeyDown = this.input.keyPresses.shift();
        boolean hasForwardImpulse = this.input.hasForwardImpulse();
        Abilities abilities = this.getAbilities();
        this.crouching = !abilities.flying
                && !this.isSwimming()
                && !this.isPassenger()
                && this.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING)
                && (this.isShiftKeyDown() || !this.isSleeping() && !this.canPlayerFitWithinBlocksAndEntitiesWhen(Pose.STANDING));
        this.input.tick(this.reportedKeys);

        if (!this.noPhysics) {
            this.moveTowardsClosestSpace(this.getX() - this.getBbWidth() * 0.35, this.getZ() + this.getBbWidth() * 0.35);
            this.moveTowardsClosestSpace(this.getX() - this.getBbWidth() * 0.35, this.getZ() - this.getBbWidth() * 0.35);
            this.moveTowardsClosestSpace(this.getX() + this.getBbWidth() * 0.35, this.getZ() - this.getBbWidth() * 0.35);
            this.moveTowardsClosestSpace(this.getX() + this.getBbWidth() * 0.35, this.getZ() + this.getBbWidth() * 0.35);
        }

        if (this.canStartSprinting()) {
            if (!hasForwardImpulse && this.client.sprintStartReportedThisTick()) {
                // - Double tapping forward within the client's sprint window option starts sprinting here; the server -
                // - never learns the option, so the client's START_SPRINTING of this tick decides. The key-based start -
                // - below needs no such evidence -
                this.setSprinting(true);
            }

            if (this.input.keyPresses.sprint()) {
                this.setSprinting(true);
            }
        }

        if (this.isSprinting()) {
            if (this.isSwimming()) {
                if (this.shouldStopSwimSprinting()) {
                    this.setSprinting(false);
                }
            } else if (this.shouldStopRunSprinting()) {
                this.setSprinting(false);
            }
        }

        boolean justToggledCreativeFlight = false;
        if (abilities.mayfly) {
            if (this.client.isLocalModeSpectator()) {
                if (!abilities.flying) {
                    abilities.flying = true;
                    justToggledCreativeFlight = true;
                    this.onUpdateAbilities();
                }
            } else if (!wasJumping && this.input.keyPresses.jump()) {
                // - The client also requires that the jump did not come from auto jump; auto jump is not -
                // - distinguishable in the reported keys, see SandboxInput -
                if (this.jumpTriggerTime == 0) {
                    this.jumpTriggerTime = 7;
                } else if (!this.isSwimming() && (this.getVehicle() == null || this.jumpableVehicle() != null)) {
                    abilities.flying = !abilities.flying;
                    if (abilities.flying && this.onGround()) {
                        this.jumpFromGround();
                    }

                    justToggledCreativeFlight = true;
                    this.onUpdateAbilities();
                    this.jumpTriggerTime = 0;
                }
            }
        }

        if (this.input.keyPresses.jump() && !justToggledCreativeFlight && !wasJumping && !this.onClimbable() && this.tryToStartFallFlying()) {
            this.client.onFallFlyingStartSent();
        }

        if (this.isInWater() && this.input.keyPresses.shift() && this.isAffectedByFluids()) {
            this.goDownInWater();
        }

        if (abilities.flying && this.isControlledCamera()) {
            int inputYa = 0;
            if (this.input.keyPresses.shift()) {
                inputYa--;
            }

            if (this.input.keyPresses.jump()) {
                inputYa++;
            }

            if (inputYa != 0) {
                this.setDeltaMovement(this.getDeltaMovement().add(0.0, inputYa * abilities.getFlyingSpeed() * 3.0F, 0.0));
            }
        }

        // - Holding jump charges the vehicle's jump, releasing it jumps and sends START_RIDING_JUMP with the power -
        PlayerRideableJumping jumpableVehicle = this.jumpableVehicle();
        if (jumpableVehicle != null && jumpableVehicle.getJumpCooldown() == 0) {
            if (this.jumpRidingTicks < 0) {
                this.jumpRidingTicks++;
                if (this.jumpRidingTicks == 0) {
                    this.jumpRidingScale = 0.0F;
                }
            }

            if (wasJumping && !this.input.keyPresses.jump()) {
                this.jumpRidingTicks = -10;
                jumpableVehicle.onPlayerJump(Mth.floor(this.getJumpRidingScale() * 100.0F));
                this.client.onRidingJumpSent(Mth.floor(this.getJumpRidingScale() * 100.0F));
            } else if (!wasJumping && this.input.keyPresses.jump()) {
                this.jumpRidingTicks = 0;
                this.jumpRidingScale = 0.0F;
            } else if (wasJumping) {
                this.jumpRidingTicks++;
                if (this.jumpRidingTicks < 10) {
                    this.jumpRidingScale = this.jumpRidingTicks * 0.1F;
                } else {
                    this.jumpRidingScale = 0.8F + 2.0F / (this.jumpRidingTicks - 9) * 0.1F;
                }
            }
        } else {
            this.jumpRidingScale = 0.0F;
        }

        super.aiStep();
        if (this.onGround() && abilities.flying && !this.client.isLocalModeSpectator()) {
            abilities.flying = false;
            this.onUpdateAbilities();
        }
    }

    private void handlePortalTransitionEffect(boolean active) {
        if (active && this.portalProcess != null && this.portalProcess.isInsidePortalThisTick()) {
            this.portalProcess.setAsInsidePortalThisTick(false);
        }
    }

    public Portal.Transition getActivePortalLocalTransition() {
        return this.portalProcess == null ? Portal.Transition.NONE : this.portalProcess.getPortalLocalTransition();
    }

    private void moveTowardsClosestSpace(double x, double z) {
        BlockPos pos = BlockPos.containing(x, this.getY(), z);
        if (this.suffocatesAt(pos)) {
            double xd = x - pos.getX();
            double zd = z - pos.getZ();
            Direction dir = null;
            double closest = Double.MAX_VALUE;
            Direction[] directions = new Direction[]{Direction.WEST, Direction.EAST, Direction.NORTH, Direction.SOUTH};

            for (Direction direction : directions) {
                double axisDistance = direction.getAxis().choose(xd, 0.0, zd);
                double distanceToEdge = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0 - axisDistance : axisDistance;
                if (distanceToEdge < closest && !this.suffocatesAt(pos.relative(direction))) {
                    closest = distanceToEdge;
                    dir = direction;
                }
            }

            if (dir != null) {
                Vec3 oldMovement = this.getDeltaMovement();
                if (dir.getAxis() == Direction.Axis.X) {
                    this.setDeltaMovement(0.1 * dir.getStepX(), oldMovement.y, oldMovement.z);
                } else {
                    this.setDeltaMovement(oldMovement.x, oldMovement.y, 0.1 * dir.getStepZ());
                }
            }
        }
    }

    private boolean suffocatesAt(BlockPos pos) {
        AABB boundingBox = this.getBoundingBox();
        AABB testArea = new AABB(pos.getX(), boundingBox.minY, pos.getZ(), pos.getX() + 1.0, boundingBox.maxY, pos.getZ() + 1.0).deflate(1.0E-7);
        return this.level().collidesWithSuffocatingBlock(this, testArea);
    }

    private boolean shouldStopRunSprinting() {
        return !this.isSprintingPossible(this.getAbilities().flying)
                || !this.input.hasForwardImpulse()
                || this.horizontalCollision && !this.minorHorizontalCollision;
    }

    private boolean shouldStopSwimSprinting() {
        return !this.isSprintingPossible(true) || !this.isInWater() || !this.input.hasForwardImpulse() && !this.onGround() && !this.input.keyPresses.shift();
    }

    private boolean isSprintingPossible(boolean allowedInShallowWater) {
        return !this.isMobilityRestricted()
                && (this.isPassenger() ? this.vehicleCanSprint(this.getVehicle()) : this.hasEnoughFoodToDoExhaustiveManoeuvres())
                && (allowedInShallowWater || !this.isInShallowWater());
    }

    private boolean canStartSprinting() {
        return !this.isSprinting()
                && this.input.hasForwardImpulse()
                && this.isSprintingPossible(this.getAbilities().flying)
                && !this.isSlowDueToUsingItem()
                && (!this.isFallFlying() || this.isUnderWater())
                && (!this.isMovingSlowly() || this.isUnderWater());
    }

    private boolean vehicleCanSprint(Entity vehicle) {
        return vehicle.canSprint() && vehicle.isLocalInstanceAuthoritative();
    }

    public @Nullable PlayerRideableJumping jumpableVehicle() {
        return this.getControlledVehicle() instanceof PlayerRideableJumping playerRideableJumping && playerRideableJumping.canJump()
                ? playerRideableJumping
                : null;
    }

    public float getJumpRidingScale() {
        return this.jumpRidingScale;
    }

    @Override
    protected boolean isHorizontalCollisionMinor(Vec3 movement) {
        float yRotInRadians = this.getYRot() * (float) (Math.PI / 180.0);
        double yRotSin = Mth.sin(yRotInRadians);
        double yRotCos = Mth.cos(yRotInRadians);
        double globalXA = this.xxa * yRotCos - this.zza * yRotSin;
        double globalZA = this.zza * yRotCos + this.xxa * yRotSin;
        double aLengthSquared = Mth.square(globalXA) + Mth.square(globalZA);
        double movementLengthSquared = Mth.square(movement.x) + Mth.square(movement.z);
        if (!(aLengthSquared < 1.0E-5F) && !(movementLengthSquared < 1.0E-5F)) {
            double dotProduct = globalXA * movement.x + globalZA * movement.z;
            double angleBetweenDesiredAndActualMovement = Math.acos(dotProduct / Math.sqrt(aLengthSquared * movementLengthSquared));
            return angleBetweenDesiredAndActualMovement < 0.13962634F;
        }
        return false;
    }

    @Override
    public boolean isSuppressingSlidingDownLadder() {
        return !this.getAbilities().flying && super.isSuppressingSlidingDownLadder();
    }

    @Override
    public boolean isUsingItem() {
        return this.startedUsingItem;
    }

    private boolean isSlowDueToUsingItem() {
        return this.isUsingItem() && !this.useItem.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT).canSprint();
    }

    private float itemUseSpeedMultiplier() {
        return this.useItem.getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT).speedMultiplier();
    }

    @Override
    public void startUsingItem(InteractionHand hand) {
        ItemStack itemStack = this.getItemInHand(hand);
        if (!itemStack.isEmpty() && !this.isUsingItem()) {
            super.startUsingItem(hand);
            this.startedUsingItem = true;
            this.usingItemHand = hand;
        }
    }

    @Override
    public void stopUsingItem() {
        super.stopUsingItem();
        this.startedUsingItem = false;
    }

    // - startUsingItem for a use the client began this many ticks ago: LivingEntity.updatingUsingItem has counted its -
    // - remaining ticks down once a tick since -
    public void startUsingItemSince(InteractionHand hand, int ticksUsed) {
        this.startUsingItem(hand);
        if (this.isUsingItem()) {
            this.useItemRemaining -= ticksUsed;
        }
    }

    @Override
    public boolean isInWaterOrRain() {
        Boolean forced = this.forcedWaterOrRain;
        return forced != null ? forced : super.isInWaterOrRain();
    }

    // - Runs the action with isInWaterOrRain giving this answer -
    public void withWaterOrRain(boolean wet, Runnable action) {
        Boolean previous = this.forcedWaterOrRain;
        this.forcedWaterOrRain = wet;
        try {
            action.run();
        } finally {
            this.forcedWaterOrRain = previous;
        }
    }

    @Override
    public InteractionHand getUsedItemHand() {
        return Objects.requireNonNullElse(this.usingItemHand, InteractionHand.MAIN_HAND);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
        super.onSyncedDataUpdated(accessor);
        if (DATA_LIVING_ENTITY_FLAGS.equals(accessor)) {
            boolean serverUsingItem = (this.entityData.get(DATA_LIVING_ENTITY_FLAGS) & 1) > 0;
            InteractionHand serverUsingHand = (this.entityData.get(DATA_LIVING_ENTITY_FLAGS) & 2) > 0 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            if (serverUsingItem && !this.startedUsingItem) {
                this.startUsingItem(serverUsingHand);
            } else if (!serverUsingItem && this.startedUsingItem) {
                this.stopUsingItem();
            }
        }
    }

    @Override
    public boolean isUnderWater() {
        return this.wasUnderwater;
    }

    @Override
    protected boolean updateIsUnderwater() {
        super.updateIsUnderwater();
        return this.wasUnderwater;
    }

    @Override
    public void onUpdateAbilities() {
        this.client.onAbilitiesSent();
    }

    @Override
    protected void tickDeath() {
        this.deathTime++;
        if (this.deathTime == 20) {
            this.remove(Entity.RemovalReason.KILLED);
        }
    }

    // - The client never heals itself; health only changes through the server's ClientboundSetHealthPacket -
    @Override
    public void heal(float heal) {
    }

    public void hurtTo(float newHealth) {
        if (this.flashOnSetHealth) {
            float dmg = this.getHealth() - newHealth;
            if (dmg <= 0.0F) {
                this.setHealth(newHealth);
                if (dmg < 0.0F) {
                    this.invulnerableTime = 10;
                }
            } else {
                this.lastHurt = dmg;
                this.invulnerableTime = 20;
                this.setHealth(newHealth);
                this.hurtDuration = 10;
                this.hurtTime = this.hurtDuration;
            }
        } else {
            this.setHealth(newHealth);
            this.flashOnSetHealth = true;
        }
    }

    public void onGameModeChanged(GameType gameType) {
        if (gameType == GameType.SPECTATOR) {
            this.setDeltaMovement(this.getDeltaMovement().with(Direction.Axis.Y, 0.0));
        }
    }

    // - One other value the client's attackStrengthTicker may have; it starts at the zero the client's player reset -
    // - its ticker to, while already holding the new main hand item -
    private static final class AlternativeAttackStrength {
        private int ticker;
        private boolean heldMainHandItem = true;
    }

    // - An attack as Player.attack made it: whether it was a knockback attack, the alternative tickers that would -
    // - have decided otherwise, and once it hurt its target, the base knockback and the knockback in total -
    private static final class AttackRecord {
        private final boolean knockbackAttack;
        private final List<Integer> tickersWithOtherKnockbackAttack;
        private boolean hurtTarget;
        private float baseKnockback;
        private float knockback;

        private AttackRecord(boolean knockbackAttack, List<Integer> tickersWithOtherKnockbackAttack) {
            this.knockbackAttack = knockbackAttack;
            this.tickersWithOtherKnockbackAttack = tickersWithOtherKnockbackAttack;
        }
    }
}
