package io.github.hellotta.clauac.simulation.player;

import com.mojang.authlib.GameProfile;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PlayerRideableJumping;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// - Port of the parts of the client-only LocalPlayer and AbstractClientPlayer that decide how the local player moves. -
// - Everything else is inherited unchanged from the vanilla Player, LivingEntity and Entity classes. Left out on -
// - purpose, because they only render, play or show something: view bobbing, walked distance, first person hands, -
// - ambient sounds, water vision, the nausea and portal spinning effect, the tutorial and every screen. Riding is not -
// - simulated at all (the session stops simulating while the player is a passenger), so neither is the riding jump -
public final class SandboxPlayer extends Player {

    private final ClientContext client;
    public SandboxInput input = new SandboxInput();
    private Input reportedKeys = Input.EMPTY;
    private boolean crouching;
    private boolean flashOnSetHealth;
    private boolean startedUsingItem;
    private @Nullable InteractionHand usingItemHand;

    public SandboxPlayer(Level level, GameProfile profile, ClientContext client) {
        super(level, profile);
        this.client = client;
    }

    // - The keys of the ServerboundPlayerInputPacket for the tick about to be simulated -
    public void setReportedKeys(Input reportedKeys) {
        this.reportedKeys = reportedKeys;
    }

    // - The client gives its player a new KeyboardInput when it joins a level, which starts without keys -
    public void replaceInput() {
        this.input = new SandboxInput();
    }

    // - Whether the living entity flags the server synchronizes say that the player is using an item -
    public boolean serverReportsItemUse() {
        return (this.entityData.get(DATA_LIVING_ENTITY_FLAGS) & 1) != 0;
    }

    @Override
    public boolean isLocalPlayer() {
        return true;
    }

    @Override
    public @Nullable GameType gameMode() {
        return this.client.playerInfoGameMode();
    }

    @Override
    public void tick() {
        if (this.client.hasClientLoaded()) {
            super.tick();
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

        if (!this.noPhysics && !this.isPassenger()) {
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
                    this.damageCooldownTime = 10;
                }
            } else {
                this.lastHurt = dmg;
                this.damageCooldownTime = 20;
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
}
