package io.github.hellotta.clauac.simulation.session;

import com.google.common.hash.HashCode;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.github.hellotta.clauac.simulation.api.Check;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.Flag;
import io.github.hellotta.clauac.simulation.api.TickOutcome;
import io.github.hellotta.clauac.simulation.player.ClientContext;
import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import io.github.hellotta.clauac.simulation.player.SandboxPlayerInfo;
import io.github.hellotta.clauac.simulation.registry.ReceivedRegistries;
import io.github.hellotta.clauac.simulation.world.SandboxClockManager;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import io.github.hellotta.clauac.simulation.world.SandboxLevelData;
import io.github.hellotta.clauac.simulation.world.SandboxRecipeContainer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChangeDifficultyPacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundMoveMinecartPacket;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.HashOps;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - Port of what the client-only ClientPacketListener and Minecraft do for the world, the entities and the local -
// - player during one play phase of a connection; the client creates a new listener after every configuration phase. -
// - Handlers keep the client's order of operations and leave out what only renders, plays sounds or shows screens. -
// - Entity handlers live in EntityHandlers, menu handlers in ContainerHandlers, and the player's own actions in -
// - SandboxGameMode -
final class PlayConnection implements ClientContext {

    private static final Logger LOGGER = LogUtils.getLogger();
    // - LocalPlayer.POSITION_REMINDER_INTERVAL -
    private static final int POSITION_REMINDER_INTERVAL = 20;
    // - LocalPlayer.sendPosition sends a position once the player moved more than this since the last one -
    private static final double MINIMUM_REPORTED_MOVEMENT = 2.0E-4;
    // - Minecraft.tick picks what the crosshair points at with a partial tick of 1 -
    private static final float TICK_PARTIAL_TICK = 1.0F;
    // - The client's timer runs at 20 ticks per second (Minecraft.deltaTracker, a DeltaTracker.Timer of 20 ticks) -
    static final float DEFAULT_TICK_MILLIS = 1000.0F / 20.0F;
    // - Every tick of the player on foot runs a second time from a snapshot, which has to give the same result: a -
    // - development check that the snapshot holds everything a tick changes, on with -Dclauac.verifyRepeatedTicks=true -
    private static final boolean VERIFY_REPEATED_TICKS = Boolean.getBoolean("clauac.verifyRepeatedTicks");
    // - A block entity this far from the player's box can still move the player when the block entities tick -
    private static final double MOVING_BLOCK_REACH = 2.0;
    // - The player pushes the entities its box touches during its tick; its movement can carry the box this far -
    private static final double PUSH_REACH = 1.0;
    // - The difference in the reported position itself, which a velocity estimate can explain (see compareWithClient) -
    private static final String POSITION_DIFFERENCE = "position";
    // - Attributes that do not change how the local player moves within a tick: they count for attacks, mining, reach, -
    // - damage the server deals, health and what is only shown -
    private static final Set<Holder<Attribute>> ATTRIBUTES_BESIDE_MOVEMENT = Set.of(
            Attributes.ARMOR, Attributes.ARMOR_TOUGHNESS, Attributes.ATTACK_DAMAGE, Attributes.ATTACK_KNOCKBACK, Attributes.ATTACK_SPEED,
            Attributes.BELOW_NAME_DISTANCE, Attributes.BLOCK_BREAK_SPEED, Attributes.BLOCK_INTERACTION_RANGE, Attributes.BURNING_TIME,
            Attributes.CAMERA_DISTANCE, Attributes.ENTITY_INTERACTION_RANGE, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE,
            Attributes.FALL_DAMAGE_MULTIPLIER, Attributes.FOLLOW_RANGE, Attributes.KNOCKBACK_RESISTANCE, Attributes.LUCK,
            Attributes.MAX_ABSORPTION, Attributes.MAX_HEALTH, Attributes.MINING_EFFICIENCY, Attributes.NAME_TAG_DISTANCE,
            Attributes.OXYGEN_BONUS, Attributes.SAFE_FALL_DISTANCE, Attributes.SPAWN_REINFORCEMENTS_CHANCE, Attributes.SUBMERGED_MINING_SPEED,
            Attributes.SWEEPING_DAMAGE_RATIO, Attributes.TEMPT_RANGE, Attributes.WAYPOINT_TRANSMIT_RANGE, Attributes.WAYPOINT_RECEIVE_RANGE
    );
    // - Entity.levelCallback, whose onMove files an entity in the section of the level's entity storage it moved to -
    private static final Field LEVEL_CALLBACK = levelCallbackField();
    // - ClientboundBlockUpdatePacket and ClientboundSectionBlocksUpdatePacket apply with these update flags -
    private static final int SERVER_BLOCK_UPDATE_FLAGS = 19;
    // - After the sandbox estimated the player's velocity from a reported movement (see correctHorizontalVelocity), -
    // - positions can differ by the rounding of that estimate: the client's exact movement is only known up to the -
    // - rounding of the two positions it lies between. That error shrinks only as fast as the velocity decays, and -
    // - each tick may round the new position either way because of it. Differences up to this many units in the last -
    // - place of the position are therefore expected for this many ticks after the last estimate -
    private static final int VELOCITY_ESTIMATE_ULPS = 8;
    private static final int VELOCITY_ESTIMATE_TICKS = 100;

    private final GameProfile localGameProfile;
    private final ReceivedRegistries registries;
    private final FeatureFlagSet enabledFeatures;
    // - ClientPacketListener.decoratedHashOpsGenerator, which hashes items for container clicks -
    private final HashedPatchMap.HashGenerator hashGenerator;
    private final Runnable inventoryResyncRequest;
    private final ProblemLog problemLog;
    // - Counts what the snapshots of the player's state cost (see captureState and restoreState) -
    private final SimulationCost cost;
    private final SandboxClockManager clockManager = new SandboxClockManager();
    private final Scoreboard scoreboard = new Scoreboard();
    private final Map<UUID, SandboxPlayerInfo> playerInfoMap = new HashMap<>();
    private SandboxRecipeContainer recipes = SandboxRecipeContainer.NONE;
    private final SandboxGameMode gameMode = new SandboxGameMode();
    private final EntityHandlers entities = new EntityHandlers(this);
    private int serverChunkRadius = 3;
    private int serverSimulationDistance = 3;
    private @Nullable SandboxLevelData levelData;
    private @Nullable SandboxLevel level;
    private @Nullable SandboxPlayer player;
    private boolean clientLoaded;
    // - Minecraft.cameraEntity -
    private @Nullable Entity cameraEntity;
    private OptionalInt removedPlayerVehicleId = OptionalInt.empty();
    // - A checked container click showed that the sandbox's items differ from the client's, in the menu with this -
    // - id. The sandbox's items are unknown until the server sends that menu's or the inventory's full contents -
    private OptionalInt unknownInventoryMenu = OptionalInt.empty();
    // - A report held back until the client's next tick decides on it (see releaseHeldReport): a MISMATCHED one that -
    // - a hotbar switch may explain, with the main hand item the player was using during that tick, or one whose -
    // - tick the sandbox simulated with a hotbar switch it inferred, which the next tick has to confirm -
    private @Nullable ClientTickReport heldReport;
    private ItemStack heldReportUsedItem = ItemStack.EMPTY;
    private @Nullable InferredHotbarSwitch heldReportInferredSwitch;
    // - Whether the alternative that stops the held tick's item use was tried there without matching -
    private boolean heldReportUseStopTried;
    // - The item whose use an alternative stopped to match the held tick; the next tick has to report the hotbar -
    // - switch that stops it -
    private ItemStack heldReportStoppedItem = ItemStack.EMPTY;
    // - Why the tick alternatives are no longer tried on this connection: a snapshot failed, or a tick repeated from -
    // - one came out differently (see tickLocalPlayer) -
    private @Nullable String alternativesDisabled;
    // - What trying the tick's alternatives at the player's tick found, for the comparison after the tick -
    private @Nullable AlternativeResult alternativeResult;
    private int ticksSinceVelocityEstimate = VELOCITY_ESTIMATE_TICKS;
    // - The difference and the outcome of the tick whose resync estimated the velocity last -
    private double estimatedAfterOffset;
    private TickOutcome estimatedAfterOutcome = TickOutcome.MATCHED;
    private final ClientTickPackets tickPackets = new ClientTickPackets();
    private final LastSentState lastSent = new LastSentState();

    PlayConnection(
            GameProfile localGameProfile, ReceivedRegistries registries, FeatureFlagSet enabledFeatures, Runnable inventoryResyncRequest, ProblemLog problemLog,
            SimulationCost cost
    ) {
        this.localGameProfile = localGameProfile;
        this.registries = registries;
        this.enabledFeatures = enabledFeatures;
        this.inventoryResyncRequest = inventoryResyncRequest;
        this.problemLog = problemLog;
        this.cost = cost;
        RegistryOps<HashCode> hashOps = registries.access().createSerializationContext(HashOps.CRC32C_INSTANCE);
        this.hashGenerator = component -> component.encodeValue(hashOps)
                .getOrThrow(message -> new IllegalArgumentException("Failed to hash " + component + ": " + message))
                .asInt();
    }

    ClientTickPackets tickPackets() {
        return this.tickPackets;
    }

    OptionalInt removedPlayerVehicleId() {
        return this.removedPlayerVehicleId;
    }

    void setRemovedPlayerVehicleId(OptionalInt removedPlayerVehicleId) {
        this.removedPlayerVehicleId = removedPlayerVehicleId;
    }

    @Override
    public @Nullable SandboxPlayerInfo getPlayerInfo(UUID profileId) {
        return this.playerInfoMap.get(profileId);
    }

    @Override
    public boolean hasClientLoaded() {
        return this.clientLoaded;
    }

    // - LocalPlayer.isControlledCamera -
    @Override
    public boolean isCameraOnPlayer() {
        return this.cameraEntity != null && this.cameraEntity == this.player;
    }

    @Override
    public boolean isLocalModeSpectator() {
        return this.gameMode.isSpectator();
    }

    @Override
    public boolean sprintStartReportedThisTick() {
        return this.tickPackets.sprintStartReported;
    }

    @Override
    public void onAbilitiesSent() {
        this.tickPackets.predictedAbilitiesSent = true;
    }

    @Override
    public void onFallFlyingStartSent() {
        this.tickPackets.predictedFallFlyingStart = true;
    }

    @Override
    public void onRidingJumpSent(int jumpPower) {
        this.tickPackets.predictedRidingJump = OptionalInt.of(jumpPower);
    }

    // - Handles a clientbound play packet the client has provably processed -
    void handle(Packet<?> packet, byte[] encodedPacket) {
        switch (packet) {
            case ClientboundLoginPacket login -> this.handleLogin(login);
            case ClientboundRespawnPacket respawn -> this.handleRespawn(respawn);
            case ClientboundUpdateTagsPacket tags -> this.registries.applyPlayTagUpdate(tags, encodedPacket);
            case ClientboundPingPacket ignored -> {
                // - Only marks how far the client has processed; see PendingClientbound -
            }
            case ClientboundPlayerInfoUpdatePacket infoUpdate -> this.handlePlayerInfoUpdate(infoUpdate);
            case ClientboundPlayerInfoRemovePacket infoRemove -> this.handlePlayerInfoRemove(infoRemove);
            case ClientboundUpdateRecipesPacket updateRecipes ->
                    this.recipes = new SandboxRecipeContainer(updateRecipes.itemSets(), updateRecipes.stonecutterRecipes());
            case ClientboundSetPlayerTeamPacket team -> this.handleSetPlayerTeam(team);
            default -> this.handleInLevel(packet, this.requireLevel(packet), this.requirePlayer(packet));
        }
    }

    private SandboxLevel requireLevel(Packet<?> packet) {
        if (this.level == null) {
            throw new IllegalStateException("Received " + packet.type() + " before the client joined a level");
        }
        return this.level;
    }

    private SandboxPlayer requirePlayer(Packet<?> packet) {
        if (this.player == null) {
            throw new IllegalStateException("Received " + packet.type() + " before the client had a player");
        }
        return this.player;
    }

    private void handleInLevel(Packet<?> packet, SandboxLevel level, SandboxPlayer player) {
        switch (packet) {
            case ClientboundLevelChunkWithLightPacket chunk ->
                    // - Light data is not applied: lighting is disabled in the sandbox and no movement code reads it -
                    level.getChunkSource().replaceWithPacketData(chunk.x(), chunk.z(), chunk.chunkData());
            case ClientboundForgetLevelChunkPacket forget -> level.getChunkSource().drop(forget.pos());
            case ClientboundChunksBiomesPacket biomes -> handleChunksBiomes(biomes, level);
            case ClientboundBlockUpdatePacket blockUpdate ->
                    level.setServerVerifiedBlockState(blockUpdate.getPos(), blockUpdate.getBlockState(), SERVER_BLOCK_UPDATE_FLAGS);
            case ClientboundSectionBlocksUpdatePacket sectionUpdate ->
                    sectionUpdate.runUpdates((pos, state) -> level.setServerVerifiedBlockState(pos, state, SERVER_BLOCK_UPDATE_FLAGS));
            case ClientboundBlockChangedAckPacket ack -> level.handleBlockChangedAck(ack.sequence());
            case ClientboundBlockEventPacket blockEvent -> level.blockEvent(blockEvent.getPos(), blockEvent.getBlock(), blockEvent.getB0(), blockEvent.getB1());
            case ClientboundBlockEntityDataPacket blockEntityData -> handleBlockEntityData(blockEntityData, level);
            case ClientboundSetChunkCacheCenterPacket center -> level.getChunkSource().updateViewCenter(center.getX(), center.getZ());
            case ClientboundSetChunkCacheRadiusPacket radius -> {
                this.serverChunkRadius = radius.getRadius();
                level.getChunkSource().updateViewRadius(radius.getRadius());
            }
            case ClientboundSetSimulationDistancePacket distance -> {
                this.serverSimulationDistance = distance.simulationDistance();
                level.setServerSimulationDistance(this.serverSimulationDistance);
            }
            case ClientboundPlayerPositionPacket position -> this.handleMovePlayer(position, level, player);
            case ClientboundPlayerRotationPacket rotation -> handleRotatePlayer(rotation, player);
            case ClientboundPlayerAbilitiesPacket abilities -> handlePlayerAbilities(abilities, player);
            case ClientboundGameEventPacket gameEvent -> this.handleGameEvent(gameEvent, level, player);
            case ClientboundChangeDifficultyPacket difficulty -> {
                SandboxLevelData data = Objects.requireNonNull(this.levelData, "a level exists, so does its data");
                data.setDifficulty(difficulty.difficulty());
                data.setDifficultyLocked(difficulty.locked());
            }
            case ClientboundExplodePacket explosion -> explosion.playerKnockback().ifPresent(player::pushFromExplosion);
            case ClientboundSetHealthPacket health -> {
                player.hurtTo(health.getHealth());
                player.getFoodData().setFoodLevel(health.getFood());
                player.getFoodData().setSaturation(health.getSaturation());
            }
            case ClientboundSetExperiencePacket experience ->
                    player.setExperienceValues(experience.getExperienceProgress(), experience.getTotalExperience(), experience.getExperienceLevel());
            case ClientboundSetCameraPacket camera -> {
                Entity entity = camera.getEntity(level);
                if (entity != null) {
                    this.cameraEntity = entity;
                }
            }
            case ClientboundInitializeBorderPacket border -> handleInitializeBorder(border, level);
            case ClientboundSetBorderCenterPacket border -> level.getWorldBorder().setCenter(border.getNewCenterX(), border.getNewCenterZ());
            case ClientboundSetBorderLerpSizePacket border ->
                    level.getWorldBorder().lerpSizeBetween(border.getOldSize(), border.getNewSize(), border.getLerpTime(), level.getGameTime());
            case ClientboundSetBorderSizePacket border -> level.getWorldBorder().setSize(border.getSize());
            case ClientboundSetBorderWarningDistancePacket border -> level.getWorldBorder().setWarningBlocks(border.getWarningBlocks());
            case ClientboundSetBorderWarningDelayPacket border -> level.getWorldBorder().setWarningTime(border.getWarningDelay());
            case ClientboundSetTimePacket time -> {
                long gameTime = time.gameTime();
                level.setTimeFromServer(gameTime);
                this.clockManager.handleUpdates(gameTime, time.clockUpdates());
                level.environmentAttributes().invalidateTickCache();
            }
            case ClientboundTickingStatePacket tickingState -> {
                TickRateManager manager = level.tickRateManager();
                manager.setTickRate(tickingState.tickRate());
                manager.setFrozen(tickingState.isFrozen());
            }
            case ClientboundTickingStepPacket tickingStep -> level.tickRateManager().setFrozenTicksToRun(tickingStep.tickSteps());
            // - Entities -
            case ClientboundAddEntityPacket addEntity -> this.entities.handleAddEntity(addEntity, level);
            case ClientboundRemoveEntitiesPacket remove -> this.entities.handleRemoveEntities(remove, level, player);
            case ClientboundSetEntityMotionPacket motion -> EntityHandlers.handleSetEntityMotion(motion, level);
            case ClientboundSetEntityDataPacket entityData -> EntityHandlers.handleSetEntityData(entityData, level);
            case ClientboundEntityPositionSyncPacket positionSync -> EntityHandlers.handleEntityPositionSync(positionSync, level, player);
            case ClientboundTeleportEntityPacket teleport -> this.entities.handleTeleportEntity(teleport, level, player);
            case ClientboundMoveEntityPacket move -> EntityHandlers.handleMoveEntity(move, level);
            case ClientboundMoveMinecartPacket minecart -> EntityHandlers.handleMinecartAlongTrack(minecart, level);
            case ClientboundRotateHeadPacket rotateHead -> EntityHandlers.handleRotateMob(rotateHead, level);
            case ClientboundSetPassengersPacket passengers -> this.entities.handleSetEntityPassengersPacket(passengers, level, player);
            case ClientboundSetEntityLinkPacket link -> EntityHandlers.handleEntityLinkPacket(link, level);
            case ClientboundEntityEventPacket entityEvent -> EntityHandlers.handleEntityEvent(entityEvent, level);
            case ClientboundDamageEventPacket damageEvent -> EntityHandlers.handleDamageEvent(damageEvent, level);
            case ClientboundHurtAnimationPacket hurtAnimation -> EntityHandlers.handleHurtAnimation(hurtAnimation, level);
            case ClientboundAnimatePacket animate -> EntityHandlers.handleAnimate(animate, level);
            case ClientboundSwingAnimationPacket swing -> EntityHandlers.handleSwingAnimation(swing, level);
            case ClientboundTakeItemEntityPacket takeItem -> EntityHandlers.handleTakeItemEntity(takeItem, level);
            case ClientboundSetEquipmentPacket equipment -> EntityHandlers.handleSetEquipment(equipment, level);
            case ClientboundUpdateAttributesPacket attributes -> EntityHandlers.handleUpdateAttributes(attributes, level);
            case ClientboundUpdateMobEffectPacket effect -> EntityHandlers.handleUpdateMobEffect(effect, level);
            case ClientboundRemoveMobEffectPacket effect -> EntityHandlers.handleRemoveMobEffect(effect, level);
            case ClientboundMoveVehiclePacket moveVehicle -> EntityHandlers.handleMoveVehicle(moveVehicle, player);
            case ClientboundProjectilePowerPacket projectilePower -> EntityHandlers.handleProjectilePowerPacket(projectilePower, level);
            // - Inventory and menus -
            case ClientboundContainerSetContentPacket content -> this.handleContainerContent(content, player);
            case ClientboundContainerSetSlotPacket slot -> ContainerHandlers.handleContainerSetSlot(slot, player);
            case ClientboundSetCursorItemPacket cursor -> ContainerHandlers.handleSetCursorItem(cursor, player);
            case ClientboundSetPlayerInventoryPacket inventory -> ContainerHandlers.handleSetPlayerInventory(inventory, player);
            case ClientboundSetHeldSlotPacket heldSlot -> ContainerHandlers.handleSetHeldSlot(heldSlot, player);
            case ClientboundContainerSetDataPacket data -> ContainerHandlers.handleContainerSetData(data, player);
            case ClientboundCooldownPacket cooldown -> ContainerHandlers.handleItemCooldown(cooldown, player);
            case ClientboundMerchantOffersPacket offers -> ContainerHandlers.handleMerchantOffers(offers, player);
            case ClientboundOpenScreenPacket openScreen -> ContainerHandlers.handleOpenScreen(openScreen, player);
            case ClientboundMountScreenOpenPacket mountScreen -> ContainerHandlers.handleMountScreenOpen(mountScreen, level, player);
            case ClientboundContainerClosePacket close -> ContainerHandlers.handleContainerClose(close, player);
            default -> throw new IllegalArgumentException("No handler for " + packet.type());
        }
    }

    private SandboxLevel createLevel(SandboxLevelData levelData, CommonPlayerSpawnInfo spawnInfo) {
        return new SandboxLevel(
                levelData,
                spawnInfo.dimension(),
                this.registries.access(),
                spawnInfo.dimensionType(),
                this.serverChunkRadius,
                this.serverSimulationDistance,
                spawnInfo.isDebug(),
                spawnInfo.seed(),
                spawnInfo.seaLevel(),
                this.clockManager,
                this.scoreboard,
                () -> this.recipes,
                this.enabledFeatures
        );
    }

    // - MultiPlayerGameMode.createPlayer; lastSentInput and wasSprinting are what LocalPlayer's constructor takes -
    private SandboxPlayer createPlayer(SandboxLevel level, Input lastSentInput, boolean wasSprinting) {
        SandboxPlayer created = new SandboxPlayer(level, this.localGameProfile, this);
        this.lastSent.reset(lastSentInput, wasSprinting);
        // - A new player object starts with its own, empty inventory, whose contents the server sends next -
        this.unknownInventoryMenu = OptionalInt.empty();
        return created;
    }

    private void handleLogin(ClientboundLoginPacket packet) {
        // - handleLogin creates a new MultiPlayerGameMode -
        this.gameMode.reset();
        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        this.serverChunkRadius = packet.chunkRadius();
        this.serverSimulationDistance = packet.simulationDistance();
        SandboxLevelData newLevelData = new SandboxLevelData(Difficulty.NORMAL, packet.hardcore(), spawnInfo.isFlat());
        this.levelData = newLevelData;
        SandboxLevel newLevel = this.createLevel(newLevelData, spawnInfo);
        this.level = newLevel;
        SandboxPlayer loginPlayer = this.player;
        if (loginPlayer == null) {
            loginPlayer = this.createPlayer(newLevel, Input.EMPTY, false);
            loginPlayer.setYRot(-180.0F);
            this.player = loginPlayer;
        }

        this.clientLoaded = false;
        loginPlayer.resetPos();
        loginPlayer.setId(packet.playerId());
        newLevel.addEntity(loginPlayer);
        newLevel.setLocalPlayer(loginPlayer);
        loginPlayer.replaceInput();
        this.gameMode.adjustPlayer(loginPlayer);
        this.cameraEntity = loginPlayer;
        loginPlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        loginPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        this.gameMode.setLocalMode(spawnInfo.gameType(), loginPlayer);
    }

    private void handleRespawn(ClientboundRespawnPacket packet) {
        SandboxPlayer oldPlayer = this.player;
        SandboxLevelData oldLevelData = this.levelData;
        SandboxLevel respawnLevel = this.level;
        if (oldPlayer == null || oldLevelData == null || respawnLevel == null) {
            throw new IllegalStateException("Received a respawn before the client joined a level");
        }
        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        ResourceKey<Level> dimensionKey = spawnInfo.dimension();
        boolean dimensionChanged = dimensionKey != oldPlayer.level().dimension();
        if (dimensionChanged) {
            SandboxLevelData newLevelData = new SandboxLevelData(oldLevelData.getDifficulty(), oldLevelData.isHardcore(), spawnInfo.isFlat());
            this.levelData = newLevelData;
            respawnLevel = this.createLevel(newLevelData, spawnInfo);
            this.level = respawnLevel;
        }

        this.cameraEntity = null;
        if (oldPlayer.hasContainerOpen()) {
            ContainerHandlers.closeScreen(oldPlayer, false);
        }
        boolean keepEntityData = packet.shouldKeep(ClientboundRespawnPacket.KEEP_ENTITY_DATA);
        SandboxPlayer newPlayer = keepEntityData
                ? this.createPlayer(respawnLevel, this.lastSent.input, oldPlayer.isSprinting())
                : this.createPlayer(respawnLevel, Input.EMPTY, false);

        this.clientLoaded = false;
        newPlayer.setId(oldPlayer.getId());
        this.player = newPlayer;
        this.cameraEntity = newPlayer;
        if (keepEntityData) {
            List<SynchedEntityData.DataValue<?>> data = oldPlayer.getEntityData().getNonDefaultValues();
            if (data != null) {
                newPlayer.getEntityData().assignValues(data);
            }

            newPlayer.setDeltaMovement(oldPlayer.getDeltaMovement());
            newPlayer.setYRot(oldPlayer.getYRot());
            newPlayer.setXRot(oldPlayer.getXRot());
        } else {
            newPlayer.resetPos();
            newPlayer.setYRot(-180.0F);
        }

        AttributeMap oldAttributes = oldPlayer.getAttributes();
        if (packet.shouldKeep(ClientboundRespawnPacket.KEEP_ATTRIBUTE_MODIFIERS)) {
            newPlayer.getAttributes().assignAllValues(oldAttributes);
        } else {
            newPlayer.getAttributes().assignBaseValues(oldAttributes);
        }

        respawnLevel.addEntity(newPlayer);
        respawnLevel.setLocalPlayer(newPlayer);
        newPlayer.replaceInput();
        this.gameMode.adjustPlayer(newPlayer);
        newPlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        newPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        this.gameMode.setLocalMode(spawnInfo.gameType(), newPlayer);
    }

    private static void handleChunksBiomes(ClientboundChunksBiomesPacket packet, SandboxLevel level) {
        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            level.getChunkSource().replaceBiomes(data.pos().x(), data.pos().z(), data.getReadBuffer());
        }

        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            level.onChunkLoaded(new ChunkPos(data.pos().x(), data.pos().z()));
        }
    }

    // - Block entities decide some collision shapes (shulker boxes) and move blocks (pistons) -
    private static void handleBlockEntityData(ClientboundBlockEntityDataPacket packet, SandboxLevel level) {
        BlockPos pos = packet.getPos();
        level.getBlockEntity(pos, packet.getType()).ifPresent(blockEntity -> {
            try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(blockEntity.problemPath(), LOGGER)) {
                blockEntity.loadWithComponents(TagValueInput.create(reporter, level.registryAccess(), packet.getTag()));
            }
        });
    }

    // - The teleport also ends the client's mining; the resulting ABORT_DESTROY_BLOCK reaches the sandbox with the -
    // - client's next tick and then changes nothing more -
    private void handleMovePlayer(ClientboundPlayerPositionPacket packet, SandboxLevel level, SandboxPlayer player) {
        if (!player.isPassenger()) {
            EntityHandlers.setValuesFromPositionPacket(packet.change(), packet.relatives(), player, false);
        }
        level.getBlockPredictions().onTeleport();
        this.gameMode.abortDestroyBlock(player, false);
    }

    // - Without the answer the client sends right away -
    private static void handleRotatePlayer(ClientboundPlayerRotationPacket packet, SandboxPlayer player) {
        Set<Relative> relatives = Relative.rotation(packet.relativeY(), packet.relativeX());
        PositionMoveRotation currentValues = PositionMoveRotation.of(player);
        PositionMoveRotation newValues = PositionMoveRotation.calculateAbsolute(currentValues, currentValues.withRotation(packet.yRot(), packet.xRot()), relatives);
        player.setYRot(newValues.yRot());
        player.setXRot(newValues.xRot());
        player.setOldRot();
    }

    // - Packets that set the player's rotation as well. When one of them is still pending before a rotation packet, -
    // - a rotation the client sends cannot be recognized as the answer to that rotation packet from its values -
    static boolean replacesPlayerRotation(Packet<?> packet) {
        return packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundTeleportEntityPacket
                || packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket
                || packet instanceof ClientboundStartConfigurationPacket;
    }

    // - Whether a ServerboundMovePlayerPacket.Rot is the answer ClientPacketListener.handleRotatePlayer sends to this -
    // - rotation packet: that answer always reports neither ground nor collision and carries the rotation the -
    // - packet results in. A regular rotation packet of a tick can look the same only if it carries that rotation -
    // - too. For a relative rotation packet the result depends on the client's rotation when it processes the -
    // - packet, which can differ from its last sent one if the mouse moved since; such an answer is then taken for -
    // - a regular packet, and the rotation packet is applied at the next pong instead -
    boolean isRotationAnswer(ServerboundMovePlayerPacket.Rot answer, ClientboundPlayerRotationPacket packet) {
        SandboxPlayer current = this.player;
        if (current == null || answer.isOnGround() || answer.horizontalCollision()) {
            return false;
        }
        float yRot = current.getYRot();
        float xRot = current.getXRot();
        float yRotO = current.yRotO;
        float xRotO = current.xRotO;
        handleRotatePlayer(packet, current);
        boolean matches = answer.getYRot(Float.NaN) == current.getYRot() && answer.getXRot(Float.NaN) == current.getXRot();
        current.setYRot(yRot);
        current.setXRot(xRot);
        current.yRotO = yRotO;
        current.xRotO = xRotO;
        return matches;
    }

    // - The client answers a position packet with its resulting position and rotation. A difference means the -
    // - sandbox's player was elsewhere before the packet, which matters for relative teleports; the sandbox takes -
    // - over the client's values -
    void verifyTeleportAnswer(ServerboundAcceptTeleportationPacket answer) {
        SandboxPlayer current = this.player;
        if (current == null || current.isPassenger()) {
            return;
        }
        boolean positionDiffers = current.getX() != answer.x() || current.getY() != answer.y() || current.getZ() != answer.z();
        if (positionDiffers || current.getYRot() != answer.yRot() || current.getXRot() != answer.xRot()) {
            // - The rotation is the client's input and may have turned since its last tick; the sandbox takes it over -
            // - like the tick's own rotation. A position that differs shows the player was elsewhere than the sandbox -
            // - thought, with a velocity the sandbox cannot know either -
            if (positionDiffers) {
                this.tickPackets.uncertainties.add("teleport result differed");
            }
            this.tickPackets.notes.add(String.format(
                    "teleport %d: sandbox %.6f %.6f %.6f %.3f %.3f, client %.6f %.6f %.6f %.3f %.3f",
                    answer.id(), current.getX(), current.getY(), current.getZ(), current.getYRot(), current.getXRot(),
                    answer.x(), answer.y(), answer.z(), answer.yRot(), answer.xRot()
            ));
            current.setPos(answer.x(), answer.y(), answer.z());
            current.setYRot(answer.yRot());
            current.setXRot(answer.xRot());
        }
    }

    private static void handlePlayerAbilities(ClientboundPlayerAbilitiesPacket packet, SandboxPlayer player) {
        player.getAbilities().flying = packet.isFlying();
        player.getAbilities().instabuild = packet.canInstabuild();
        player.getAbilities().invulnerable = packet.isInvulnerable();
        player.getAbilities().mayfly = packet.canFly();
        player.getAbilities().setFlyingSpeed(packet.getFlyingSpeed());
        player.getAbilities().setWalkingSpeed(packet.getWalkingSpeed());
    }

    // - Only the events that change the level or the player; the others show messages, screens or effects -
    private void handleGameEvent(ClientboundGameEventPacket packet, SandboxLevel level, SandboxPlayer player) {
        ClientboundGameEventPacket.Type event = packet.getEvent();
        float paramFloat = packet.getParam();
        int param = Mth.floor(paramFloat + 0.5F);
        if (event == ClientboundGameEventPacket.START_RAINING) {
            level.setRainLevel(0.0F);
        } else if (event == ClientboundGameEventPacket.STOP_RAINING) {
            level.setRainLevel(1.0F);
        } else if (event == ClientboundGameEventPacket.CHANGE_GAME_MODE) {
            this.gameMode.setLocalMode(GameType.byId(param), player);
        } else if (event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
            level.setRainLevel(paramFloat);
        } else if (event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
            level.setThunderLevel(paramFloat);
        }
    }

    private static void handleInitializeBorder(ClientboundInitializeBorderPacket packet, SandboxLevel level) {
        WorldBorder border = level.getWorldBorder();
        border.setCenter(packet.getNewCenterX(), packet.getNewCenterZ());
        long lerpTime = packet.getLerpTime();
        if (lerpTime > 0L) {
            border.lerpSizeBetween(packet.getOldSize(), packet.getNewSize(), lerpTime, level.getGameTime());
        } else {
            border.setSize(packet.getNewSize());
        }

        border.setAbsoluteMaxSize(packet.getNewAbsoluteMaxSize());
        border.setWarningBlocks(packet.getWarningBlocks());
        border.setWarningTime(packet.getWarningTime());
    }

    private void handlePlayerInfoRemove(ClientboundPlayerInfoRemovePacket packet) {
        for (UUID profileId : packet.profileIds()) {
            this.playerInfoMap.remove(profileId);
        }
    }

    private void handlePlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket packet) {
        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
            this.playerInfoMap.putIfAbsent(entry.profileId(), new SandboxPlayerInfo(Objects.requireNonNull(entry.profile())));
        }

        for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
            SandboxPlayerInfo info = this.playerInfoMap.get(entry.profileId());
            if (info == null) {
                LOGGER.warn("Ignoring player info update for unknown player {} ({})", entry.profileId(), packet.actions());
            } else if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
                if (info.getGameMode() != entry.gameMode() && this.player != null && this.player.getUUID().equals(entry.profileId())) {
                    this.player.onGameModeChanged(entry.gameMode());
                }
                info.setGameMode(entry.gameMode());
            }
        }
    }

    // - Teams decide which entities push each other (Team.CollisionRule, see EntitySelector.pushableBy) -
    private void handleSetPlayerTeam(ClientboundSetPlayerTeamPacket packet) {
        ClientboundSetPlayerTeamPacket.Action teamAction = packet.getTeamAction();
        PlayerTeam team;
        if (teamAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
            team = this.scoreboard.addPlayerTeam(packet.getName());
        } else {
            team = this.scoreboard.getPlayerTeam(packet.getName());
            if (team == null) {
                LOGGER.warn("Received packet for unknown team {}: team action: {}, player action: {}", packet.getName(), packet.getTeamAction(), packet.getPlayerAction());
                return;
            }
        }

        Optional<ClientboundSetPlayerTeamPacket.Parameters> parameters = packet.getParameters();
        parameters.ifPresent(p -> {
            team.setDisplayName(p.displayName());
            team.setColor(p.color());
            team.unpackOptions(p.options());
            team.setNameTagVisibility(p.nameTagVisibility());
            team.setCollisionRule(p.collisionRule());
            team.setPlayerPrefix(p.playerPrefix());
            team.setPlayerSuffix(p.playerSuffix());
        });
        ClientboundSetPlayerTeamPacket.Action playerAction = packet.getPlayerAction();
        if (playerAction == ClientboundSetPlayerTeamPacket.Action.ADD) {
            for (String member : packet.getPlayers()) {
                this.scoreboard.addPlayerToTeam(member, team);
            }
        } else if (playerAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            for (String member : packet.getPlayers()) {
                this.scoreboard.removePlayerFromTeam(member, team);
            }
        }

        if (teamAction == ClientboundSetPlayerTeamPacket.Action.REMOVE) {
            this.scoreboard.removePlayerTeam(team);
        }
    }

    private void handleContainerContent(ClientboundContainerSetContentPacket packet, SandboxPlayer player) {
        boolean wholeInventory = ContainerHandlers.handleContainerContent(packet, player);
        if (this.unknownInventoryMenu.isPresent() && (wholeInventory || this.unknownInventoryMenu.getAsInt() == packet.containerId())) {
            this.unknownInventoryMenu = OptionalInt.empty();
        }
    }

    // - The sandbox cannot know which items the client has; it asks the plugin to have the server send them all -
    private void markInventoryUnknown(int menuId) {
        boolean alreadyUnknown = this.unknownInventoryMenu.isPresent();
        this.unknownInventoryMenu = OptionalInt.of(menuId);
        if (!alreadyUnknown) {
            this.inventoryResyncRequest.run();
        }
    }

    // - ServerboundPlayerLoadedPacket: the client reports it once its player starts ticking -
    void onPlayerLoaded() {
        this.clientLoaded = true;
    }

    // - Menu interactions happen on screens, between the client's ticks, so they apply right away -
    void onContainerClick(ServerboundContainerClickPacket packet) {
        SandboxPlayer clicking = this.requirePlayer(packet);
        List<String> differences = SandboxGameMode.handleContainerInput(packet, clicking, this.hashGenerator);
        if (!differences.isEmpty()) {
            this.tickPackets.notes.add("container click differs: " + String.join(", ", differences));
            this.markInventoryUnknown(packet.containerId());
        }
    }

    // - LocalPlayer.closeContainer: the client closes its screen; menu 0 is the player's own inventory screen -
    void onContainerClose(int containerId) {
        SandboxPlayer closing = this.requirePlayerForAction();
        if (containerId != closing.containerMenu.containerId) {
            this.tickPackets.notes.add("the client closed menu " + containerId + " while the sandbox has " + closing.containerMenu.containerId + " open");
        }
        ContainerHandlers.closeScreen(closing, containerId == InventoryMenu.CONTAINER_ID);
        if (this.unknownInventoryMenu.isPresent()) {
            // - The server now sends the player's own inventory when asked -
            this.unknownInventoryMenu = OptionalInt.empty();
            this.markInventoryUnknown(InventoryMenu.CONTAINER_ID);
        }
    }

    // - The creative inventory screen sets the slots itself and reports each change -
    void onCreativeModeSlot(ServerboundSetCreativeModeSlotPacket packet) {
        SandboxPlayer creative = this.requirePlayer(packet);
        int slot = packet.slotNum();
        if (slot >= InventoryMenu.CRAFT_SLOT_START && slot < InventoryMenu.SHIELD_SLOT + 1) {
            creative.inventoryMenu.getSlot(slot).set(packet.itemStack());
        }
    }

    void onContainerButtonClick(int containerId, int buttonId) {
        SandboxGameMode.handleContainerButtonClick(this.requirePlayerForAction(), containerId, buttonId);
    }

    void onSelectTrade(int tradeIndex) {
        SandboxGameMode.handleSelectTrade(this.requirePlayerForAction(), tradeIndex);
    }

    void onRenameItem(String name) {
        SandboxGameMode.handleRenameItem(this.requirePlayerForAction(), name);
    }

    void onSlotStateChanged(int slotId, int containerId, boolean enabled) {
        if (!SandboxGameMode.handleSlotStateChanged(this.requirePlayerForAction(), slotId, containerId, enabled)) {
            this.tickPackets.notes.add("the client toggled crafter slot " + slotId + ", which is not a crafting slot");
        }
    }

    void onSelectBundleItem(int slotId, int selectedItemIndex) {
        SandboxPlayer selecting = this.requirePlayerForAction();
        if (!SandboxGameMode.handleSelectBundleItem(selecting, slotId, selectedItemIndex)) {
            this.tickPackets.notes.add("bundle selection in slot " + slotId + " does not match one bundle");
            this.markInventoryUnknown(selecting.containerMenu.containerId);
        }
    }

    private SandboxPlayer requirePlayerForAction() {
        if (this.player == null) {
            throw new IllegalStateException("The client acted before it had a player");
        }
        return this.player;
    }

    // - Minecraft.tick for one client tick, then the comparison of LocalPlayer.sendChanges with what the client sent. -
    // - Returns the reports that are final, in the order of the client's ticks: a report may be held back for one tick. -
    // - A failure of the simulation makes the tick MISMATCHED instead of ending the simulation, so that no packet a -
    // - client sends can switch it off -
    List<ClientTickReport> tick(long clientTick) {
        List<ClientTickReport> reports = new ArrayList<>(2);
        try {
            ClientTickReport report = this.simulateTick(clientTick, reports);
            if (report != null) {
                reports.add(report);
            }
        } catch (RuntimeException problem) {
            this.problemLog.log("failed to simulate client tick " + clientTick, problem);
            // - A report held back from the previous tick that this tick failed before deciding on still goes first, -
            // - so that the reports stay in the order of the client's ticks -
            ClientTickReport held = this.takeHeldReport("the next tick, which had to report the hotbar switch this tick assumed, could not be simulated");
            if (held != null) {
                reports.add(held);
            }
            reports.add(this.unsimulatedTick(clientTick, new Flag(Check.SIMULATION_FAILURE, "the simulation of this tick failed (" + problem + ")")));
        }
        // - ClientLevel.tick, after the player sent its movement; a failure here shows in the next tick -
        String levelTickFailure = null;
        SandboxLevel tickLevel = this.level;
        if (tickLevel != null) {
            try {
                tickLevel.tick();
            } catch (RuntimeException problem) {
                this.problemLog.log("failed to tick its level after client tick " + clientTick, problem);
                levelTickFailure = "the level's tick after the previous client tick failed (" + problem + ")";
            }
        }
        this.tickPackets.reset();
        if (levelTickFailure != null) {
            this.tickPackets.reject(Check.SIMULATION_FAILURE, levelTickFailure);
        }
        return reports;
    }

    // - A tick the client ended sooner than real time allows (see TickBudget) is not simulated, so that ending ticks -
    // - faster cannot make the simulation fall behind: the client's reported state is taken over, and neither the -
    // - player nor the level with its entities ticks. A report held back from the previous tick goes first -
    List<ClientTickReport> skipTick(long clientTick, Flag rejection) {
        List<ClientTickReport> reports = new ArrayList<>(2);
        ClientTickReport held = this.takeHeldReport("the next tick, which had to report the hotbar switch this tick assumed, came too soon to be simulated");
        if (held != null) {
            reports.add(held);
        }
        reports.add(this.unsimulatedTick(clientTick, rejection));
        this.tickPackets.reset();
        return reports;
    }

    // - Minecraft.getTickTargetMillis: the client ticks no faster than its timer, and slower while the level runs -
    // - normally at a lower tick rate -
    float clientTickMillis() {
        SandboxLevel tickLevel = this.level;
        if (tickLevel != null) {
            TickRateManager manager = tickLevel.tickRateManager();
            if (manager.runsNormally()) {
                return Math.max(DEFAULT_TICK_MILLIS, manager.millisecondsPerTick());
            }
        }
        return DEFAULT_TICK_MILLIS;
    }

    // - Everything of a client tick up to the comparison. Returns the tick's report, or null when it is held back; a -
    // - report released from the previous tick goes to reports first -
    private @Nullable ClientTickReport simulateTick(long clientTick, List<ClientTickReport> reports) {
        Input reportedKeys = this.lastSent.input;
        boolean reportedSprinting = this.lastSent.sprinting;
        SandboxLevel tickLevel = this.level;
        SandboxPlayer tickPlayer = this.player;
        if (tickLevel == null || tickPlayer == null) {
            throw new IllegalStateException("the client ended a tick before it joined a level, which a vanilla client never does");
        }
        this.releaseHeldReport(tickPlayer, reports);
        tickLevel.tickRateManager().tick();
        // - The rotation of the whole tick, which the client's key and mouse actions already used. A riding player -
        // - reports it after its vehicle turned it during the tick -
        ServerboundMovePlayerPacket movePacket = this.tickPackets.movePacket;
        if (movePacket != null && movePacket.hasRotation()) {
            tickPlayer.setYRot(movePacket.getYRot(tickPlayer.getYRot()) - this.passengerTurnOfTick(tickPlayer));
            tickPlayer.setXRot(movePacket.getXRot(tickPlayer.getXRot()));
        }
        tickPlayer.setReportedKeys(reportedKeys);
        this.performTickActions(tickLevel, tickPlayer);
        if (!this.clientLoaded || tickPlayer.isRemoved()) {
            // - The client ticks its level but not the player, and sends no movement -
            tickLevel.tickEntities();
            tickLevel.tickBlockEntities();
            List<String> notes = new ArrayList<>(this.tickPackets.notes);
            notes.add(this.clientLoaded ? "player removed" : "client has not loaded the level");
            return this.notSimulated(clientTick, tickPlayer, reportedSprinting, notes);
        }
        InferredHotbarSwitch inferredSwitch = this.inferHotbarSwitch(tickPlayer);
        // - LivingEntity.updatingUsingItem, at the start of the player's tick, stops the use once the used hand holds -
        // - another item -
        ItemStack usedMainHandItem = tickPlayer.isUsingItem() && tickPlayer.getUsedItemHand() == InteractionHand.MAIN_HAND
                ? tickPlayer.getUseItem().copy()
                : ItemStack.EMPTY;
        Vec3 positionBeforeTick = tickPlayer.position();
        Entity vehicleBeforeTick = tickPlayer.getRootVehicle();
        Vec3 vehiclePositionBeforeTick = vehicleBeforeTick.position();
        this.alternativeResult = null;
        tickLevel.setLocalPlayerTick(entity -> this.tickLocalPlayer(tickLevel, tickPlayer));
        try {
            tickLevel.tickEntities();
        } finally {
            tickLevel.setLocalPlayerTick(null);
        }
        tickLevel.tickBlockEntities();
        List<String> uncertainties = new ArrayList<>(this.tickPackets.uncertainties);
        this.collectOngoingUncertainties(uncertainties);
        List<String> notes = new ArrayList<>(this.tickPackets.notes);
        this.judgeAlternatives(uncertainties, notes);
        ClientTickReport report;
        ItemStack heldUsedItem = ItemStack.EMPTY;
        ItemStack heldStoppedItem = ItemStack.EMPTY;
        // - LocalPlayer.sendChanges decides after the tick whether the player rides -
        if (tickPlayer.isPassenger()) {
            Vec3 steeredVehicleBeforeTick = tickPlayer.getRootVehicle() == vehicleBeforeTick ? vehiclePositionBeforeTick : null;
            report = this.compareRiding(clientTick, tickPlayer, steeredVehicleBeforeTick, reportedSprinting, uncertainties, notes);
        } else {
            report = this.compareWithClient(clientTick, tickPlayer, positionBeforeTick, reportedSprinting, uncertainties, notes);
            if (this.alternativeResult instanceof AlternativeResult.Matched matched) {
                for (TickAlternative alternative : matched.combination()) {
                    if (!alternative.stoppedItem().isEmpty()) {
                        heldStoppedItem = alternative.stoppedItem();
                    }
                }
            } else if (report.outcome() == TickOutcome.MISMATCHED && this.tickPackets.rejections.isEmpty()) {
                heldUsedItem = usedMainHandItem;
            }
        }
        if (inferredSwitch != null || !heldUsedItem.isEmpty() || !heldStoppedItem.isEmpty()) {
            this.heldReport = report;
            this.heldReportUsedItem = heldUsedItem;
            this.heldReportUseStopTried = this.alternativeResult instanceof AlternativeResult.NoneMatched;
            this.heldReportStoppedItem = heldStoppedItem;
            this.heldReportInferredSwitch = inferredSwitch;
            return null;
        }
        return report;
    }

    // - ClientLevel.tickEntities reaching the local player while it rides nothing (see SandboxLevel.setLocalPlayerTick). -
    // - A tick with alternatives runs from a snapshot: when the simulated tick differs from what the client reported, -
    // - the alternatives and their combinations run from the same start, and the first that matches stays. When none -
    // - matches, the simulated tick runs again and has to come out as the first time, which shows that the snapshot -
    // - held everything the tick changed. Blocks moving next to the player (pistons, shulker boxes) only move it -
    // - after this point, so the tick is not judged here then; neither is it once snapshots failed on this connection -
    private void tickLocalPlayer(SandboxLevel level, SandboxPlayer player) {
        List<TickAlternative> alternatives = this.tickPackets.alternatives;
        if (player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND) {
            alternatives.add(stoppedItemUse(player));
        }
        if (alternatives.isEmpty() && !VERIFY_REPEATED_TICKS) {
            level.tickNonPassenger(player);
            return;
        }
        String unavailable = this.alternativesDisabled;
        if (unavailable == null && level.hasEntityMovingBlockEntityNear(player.getBoundingBox().inflate(MOVING_BLOCK_REACH))) {
            unavailable = "blocks move next to the player";
        }
        TickStart start = null;
        if (unavailable == null) {
            try {
                start = this.saveTickStart(level, player);
            } catch (StateSnapshot.SnapshotException problem) {
                unavailable = this.disableAlternatives("the player's state could not be saved", problem);
            }
        }
        if (start == null) {
            level.tickNonPassenger(player);
            if (!alternatives.isEmpty()) {
                this.alternativeResult = new AlternativeResult.Untried(unavailable);
            }
            return;
        }
        try {
            level.tickNonPassenger(player);
            if (player.isRemoved()) {
                // - The tick removed the player from the level (LivingEntity.tickDeath), which the level's entity -
                // - storage records outside the player: running the tick again would remove it a second time -
                if (!alternatives.isEmpty()) {
                    this.alternativeResult = new AlternativeResult.Untried("the player's tick removed it from the level");
                }
                return;
            }
            if (alternatives.isEmpty() || this.slotDifferences(player).isEmpty()) {
                if (!alternatives.isEmpty()) {
                    this.alternativeResult = new AlternativeResult.SimulatedMatched();
                }
                if (VERIFY_REPEATED_TICKS) {
                    String difference = this.repeatTick(level, player, start, this.captureState(start.roots()));
                    if (difference != null) {
                        this.disableAlternatives("a repeated tick came out differently", new StateSnapshot.SnapshotException(difference));
                    }
                }
                return;
            }
            StateSnapshot simulatedEnd = this.captureState(start.roots());
            for (List<TickAlternative> combination : combinationsOf(alternatives)) {
                this.restoreTickStart(start, player);
                for (TickAlternative alternative : combination) {
                    alternative.change().apply(player);
                }
                level.tickNonPassenger(player);
                if (this.slotDifferences(player).isEmpty()) {
                    this.alternativeResult = new AlternativeResult.Matched(combination);
                    return;
                }
            }
            String difference = this.repeatTick(level, player, start, simulatedEnd);
            if (difference != null) {
                this.alternativeResult = new AlternativeResult.Untried(
                        this.disableAlternatives("a repeated tick came out differently", new StateSnapshot.SnapshotException(difference)));
            } else {
                this.alternativeResult = new AlternativeResult.NoneMatched();
            }
        } catch (StateSnapshot.SnapshotException problem) {
            this.disableAlternatives("the player's state could not be restored", problem);
            throw new IllegalStateException("the player's state could not be restored to try the tick's alternatives", problem);
        }
    }

    // - Runs the simulated tick again from its start and returns where the result differs from the first run's, or -
    // - null -
    private @Nullable String repeatTick(SandboxLevel level, SandboxPlayer player, TickStart start, StateSnapshot firstEnd) throws StateSnapshot.SnapshotException {
        this.restoreTickStart(start, player);
        level.tickNonPassenger(player);
        return firstEnd.firstDifference(this.captureState(start.roots()));
    }

    // - What the player's tick starts from: the player with the level's random, which its tick may draw from, what the -
    // - tick reports through ClientContext, and the motion of the entities it may push -
    private TickStart saveTickStart(SandboxLevel level, SandboxPlayer player) throws StateSnapshot.SnapshotException {
        List<Object> roots = List.of(player, level.getRandom());
        AABB pushArea = player.getBoundingBox().expandTowards(player.getDeltaMovement()).inflate(PUSH_REACH);
        List<EntityMotion> pushable = new ArrayList<>();
        for (Entity entity : level.getEntities(player, pushArea)) {
            pushable.add(new EntityMotion(entity, entity.getDeltaMovement(), entity.needsSync));
        }
        ClientTickPackets packets = this.tickPackets;
        return new TickStart(roots, this.captureState(roots), packets.predictedAbilitiesSent, packets.predictedFallFlyingStart,
                packets.predictedRidingJump, pushable);
    }

    // - Every snapshot of the player's state is taken and restored through these two, which count what they cost -
    private StateSnapshot captureState(List<Object> roots) throws StateSnapshot.SnapshotException {
        long start = System.nanoTime();
        StateSnapshot snapshot = StateSnapshot.capture(roots);
        this.cost.snapshotTaken(System.nanoTime() - start, snapshot.objectCount());
        return snapshot;
    }

    private void restoreState(StateSnapshot snapshot) throws StateSnapshot.SnapshotException {
        long start = System.nanoTime();
        snapshot.restore();
        this.cost.snapshotRestored(System.nanoTime() - start);
    }

    private void restoreTickStart(TickStart start, SandboxPlayer player) throws StateSnapshot.SnapshotException {
        this.restoreState(start.snapshot());
        ClientTickPackets packets = this.tickPackets;
        packets.predictedAbilitiesSent = start.predictedAbilitiesSent();
        packets.predictedFallFlyingStart = start.predictedFallFlyingStart();
        packets.predictedRidingJump = start.predictedRidingJump();
        for (EntityMotion motion : start.pushable()) {
            motion.entity().setDeltaMovement(motion.deltaMovement());
            motion.entity().needsSync = motion.needsSync();
        }
        // - The level's entity storage files the player by the section it moved into last -
        notifyMoved(player);
    }

    // - The alternative that the tick's key handling switched the hotbar slot away from the item in use, which -
    // - LivingEntity.updatingUsingItem answers with stopUsingItem at the start of the player's tick. The client -
    // - reports such a switch only at the start of its next tick, which then has to confirm it -
    private static TickAlternative stoppedItemUse(SandboxPlayer player) {
        return new TickAlternative(
                "a hotbar switch may have stopped the item use in this tick",
                "the item use a hotbar switch in this tick stopped",
                player.getUseItem().copy(),
                SandboxPlayer::stopUsingItem
        );
    }

    // - Every non-empty combination of the alternatives, the smaller ones first, each in the order of the list -
    private static List<List<TickAlternative>> combinationsOf(List<TickAlternative> alternatives) {
        List<List<TickAlternative>> combinations = new ArrayList<>();
        int count = alternatives.size();
        for (int size = 1; size <= count; size++) {
            for (int mask = 1; mask < 1 << count; mask++) {
                if (Integer.bitCount(mask) != size) {
                    continue;
                }
                List<TickAlternative> combination = new ArrayList<>();
                for (int index = 0; index < count; index++) {
                    if ((mask & 1 << index) != 0) {
                        combination.add(alternatives.get(index));
                    }
                }
                combinations.add(List.copyOf(combination));
            }
        }
        return combinations;
    }

    // - Stops trying alternatives on this connection; returns the reason, which the problem log shows once -
    private String disableAlternatives(String what, StateSnapshot.SnapshotException problem) {
        this.problemLog.log("stopped trying the alternatives of uncertain ticks: " + what, problem);
        String reason = what + " (" + problem.getMessage() + ")";
        this.alternativesDisabled = reason;
        return reason;
    }

    // - The tick's alternatives in its notes and uncertainties: those that could not be tried leave their -
    // - uncertainty, as they always did; the others a note on what trying them found. The alternative that stops an -
    // - item use only counts once the next tick reports the hotbar switch; until then the held report stands in for -
    // - its uncertainty (see releaseHeldReport), and a tick that needed none of them tells nothing about it -
    private void judgeAlternatives(List<String> uncertainties, List<String> notes) {
        List<TickAlternative> alternatives = this.tickPackets.alternatives;
        List<TickAlternative> uncertain = alternatives.stream().filter(alternative -> alternative.stoppedItem().isEmpty()).toList();
        switch (this.alternativeResult) {
            case null -> addUncertainties(uncertainties, uncertain);
            case AlternativeResult.Untried untried -> {
                addUncertainties(uncertainties, uncertain);
                if (!uncertain.isEmpty()) {
                    notes.add("alternatives not tried: " + untried.reason());
                }
            }
            case AlternativeResult.SimulatedMatched ignored -> {
                if (!uncertain.isEmpty()) {
                    notes.add("matched as simulated, not needing: " + descriptionsOf(uncertain));
                }
            }
            case AlternativeResult.Matched matched -> notes.add("matched with: " + descriptionsOf(matched.combination()));
            case AlternativeResult.NoneMatched ignored -> notes.add("no alternative matched: " + descriptionsOf(alternatives));
        }
    }

    private static void addUncertainties(List<String> uncertainties, List<TickAlternative> alternatives) {
        for (TickAlternative alternative : alternatives) {
            if (!uncertainties.contains(alternative.uncertainty())) {
                uncertainties.add(alternative.uncertainty());
            }
        }
    }

    private static String descriptionsOf(List<TickAlternative> alternatives) {
        return String.join(", ", alternatives.stream().map(TickAlternative::description).toList());
    }

    // - A hotbar key pressed during the tick's key handling (Minecraft.handleKeybinds, before any other key) changes -
    // - the held item at once, but MultiPlayerGameMode reports the new slot only at the start of the next tick unless -
    // - an action of this tick needed it first. While the player rides a vehicle it steers by what it holds (a pig -
    // - by a carrot on a stick, a strider by a warped fungus on a stick), this tick's packets show such a switch all -
    // - the same: LocalPlayer.sendChanges sends the vehicle's position exactly while the client steers it. When they -
    // - contradict the held item and no slot was reported after the tick's first packet, the sandbox selects the -
    // - first hotbar slot that explains them. The next tick has to report the slot (see confirmInferredHotbarSwitch) -
    private @Nullable InferredHotbarSwitch inferHotbarSwitch(SandboxPlayer player) {
        Entity vehicle = player.getRootVehicle();
        boolean clientSteers = this.tickPackets.vehicleMove != null;
        if (vehicle == player || vehicle.isLocalInstanceAuthoritative() == clientSteers) {
            return null;
        }
        List<Packet<?>> actions = this.tickPackets.actions;
        for (int index = 1; index < actions.size(); index++) {
            if (actions.get(index) instanceof ServerboundSetCarriedItemPacket) {
                return null;
            }
        }
        Inventory inventory = player.getInventory();
        int previousSlot = inventory.getSelectedSlot();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (slot == previousSlot) {
                continue;
            }
            inventory.setSelectedSlot(slot);
            if (vehicle.isLocalInstanceAuthoritative() == clientSteers) {
                // - The hotbar key came before the tick's other key handling, which the sandbox replayed already -
                boolean leadingReport = !actions.isEmpty() && actions.getFirst() instanceof ServerboundSetCarriedItemPacket;
                if (actions.size() > (leadingReport ? 1 : 0)) {
                    this.tickPackets.uncertainties.add("the tick's actions came after a hotbar key that only the vehicle's packets showed");
                }
                this.tickPackets.notes.add("inferred a hotbar key for slot " + (slot + 1) + " from " + (clientSteers ? "steering" : "no longer steering") + " the vehicle");
                return new InferredHotbarSwitch(previousSlot, slot, vehicle, clientSteers);
            }
        }
        inventory.setSelectedSlot(previousSlot);
        return null;
    }

    // - How far the player's vehicle turns it during this tick, which the rotation a riding player reports already -
    // - includes. AbstractBoat.positionRider turns a passenger the local client is authoritative for (the local -
    // - player always is) by the boat's deltaRotation. A boat the client steers turns itself by the same amount -
    // - (controlBoat); the client reports the boat's rotation after the tick, and the boat's rotation before it is -
    // - the one the client reported last or the sandbox took over. A boat the client does not steer has no -
    // - deltaRotation (AbstractBoat.tick). The turn is exact up to the rounding of these float rotations, which -
    // - AbstractBoat.clampRotation adds to at every frame anyway (Entity.turn calls onPassengerTurned) -
    private float passengerTurnOfTick(SandboxPlayer player) {
        ServerboundMoveVehiclePacket vehicleMove = this.tickPackets.vehicleMove;
        if (vehicleMove != null && player.getVehicle() instanceof AbstractBoat boat && boat.isLocalInstanceAuthoritative()
                && !player.is(EntityTypeTags.CAN_TURN_IN_BOATS)) {
            return vehicleMove.movingTo().yRot() - boat.getYRot();
        }
        return 0.0F;
    }

    // - Minecart.positionRider turns a passenger player with the experimental minecart movement, but only while the -
    // - client's "rotate with minecart" option is on (LocalPlayer.shouldRotateWithMinecart), which the server never -
    // - learns. The rotation such a player acted with during a tick is then unknown -
    private static boolean passengerTurnUnknown(SandboxPlayer player) {
        return player.getVehicle() instanceof Minecart && AbstractMinecart.useExperimentalMovement(player.level());
    }

    // - An action whose outcome depends on the rotation the player had during the tick -
    private void checkActionRotation(SandboxPlayer player, String action) {
        if (passengerTurnUnknown(player)) {
            this.tickPackets.uncertainties.add(action + " with a rotation the minecart may have turned");
        }
    }

    // - A tick that is not simulated, since its simulation failed partway or it came too soon (see skipTick). Nothing -
    // - the client sent is checked, so the tick is MISMATCHED with the reason, and the sandbox takes over what the -
    // - client reported, as after a mismatch, so that the next tick starts from where the client is -
    private ClientTickReport unsimulatedTick(long clientTick, Flag rejection) {
        ClientTickPackets packets = this.tickPackets;
        ReportedState reported = this.reportedState();
        boolean reportedSprinting = this.lastSent.sprinting;
        List<Flag> rejections = new ArrayList<>(packets.rejections);
        rejections.add(rejection);
        List<String> notes = new ArrayList<>(packets.notes);
        notes.add(ClientTickPackets.rejectionNote(rejections));
        double predictedX = Double.NaN;
        double predictedY = Double.NaN;
        double predictedZ = Double.NaN;
        boolean predictedOnGround = false;
        boolean predictedHorizontalCollision = false;
        boolean predictedSprinting = false;
        SandboxPlayer failedPlayer = this.player;
        if (failedPlayer != null) {
            predictedX = failedPlayer.getX();
            predictedY = failedPlayer.getY();
            predictedZ = failedPlayer.getZ();
            predictedOnGround = failedPlayer.onGround();
            predictedHorizontalCollision = failedPlayer.horizontalCollision;
            predictedSprinting = failedPlayer.isSprinting();
            adoptReportedState(failedPlayer, reported, reportedSprinting, packets);
        }
        if (this.isCameraOnPlayer()) {
            this.lastSent.positionReminder++;
        }
        this.advanceLastSent(reported);
        return new ClientTickReport(
                clientTick, TickOutcome.MISMATCHED,
                predictedX, predictedY, predictedZ, predictedOnGround, predictedHorizontalCollision, predictedSprinting,
                reported.positionReported(), reported.x(), reported.y(), reported.z(), reported.onGround(), reported.horizontalCollision(), reportedSprinting,
                Double.NaN, null, rejections, notes
        );
    }

    // - The first thing the client does in a tick is MultiPlayerGameMode.tick, which reports the hotbar slot once it -
    // - differs from the one last reported. The slot may have changed between the ticks (mouse wheel) or during the -
    // - previous tick's key handling (Minecraft.handleKeybinds), when that tick's player already held the new item; -
    // - the packets do not tell which. Returns that report when this tick starts with it and it selects another slot -
    private @Nullable ServerboundSetCarriedItemPacket leadingHotbarSwitch(SandboxPlayer player) {
        List<Packet<?>> actions = this.tickPackets.actions;
        if (!actions.isEmpty() && actions.getFirst() instanceof ServerboundSetCarriedItemPacket carriedItem
                && Inventory.isHotbarSlot(carriedItem.getSlot()) && carriedItem.getSlot() != player.getInventory().getSelectedSlot()) {
            return carriedItem;
        }
        return null;
    }

    // - Decides on the report held back from the previous tick, before this tick's actions. A hotbar switch the -
    // - sandbox inferred for that tick has to be reported now, and so has the one an alternative assumed when it -
    // - stopped the item use in that tick. A held mismatch may come from a hotbar switch that stopped the item use -
    // - as well; when that alternative was tried and did not match either, only the new item's attributes can still -
    // - explain it. If this tick reports such a switch, the held tick is unverified; otherwise its mismatch stands -
    private void releaseHeldReport(SandboxPlayer player, List<ClientTickReport> reports) {
        ClientTickReport held = this.heldReport;
        if (held == null) {
            return;
        }
        this.heldReport = null;
        ItemStack usedItem = this.heldReportUsedItem;
        this.heldReportUsedItem = ItemStack.EMPTY;
        boolean useStopTried = this.heldReportUseStopTried;
        this.heldReportUseStopTried = false;
        ItemStack stoppedItem = this.heldReportStoppedItem;
        this.heldReportStoppedItem = ItemStack.EMPTY;
        InferredHotbarSwitch inferredSwitch = this.heldReportInferredSwitch;
        this.heldReportInferredSwitch = null;
        if (inferredSwitch != null) {
            held = this.confirmInferredHotbarSwitch(held, inferredSwitch, player);
        }
        ServerboundSetCarriedItemPacket leadingSwitch = this.leadingHotbarSwitch(player);
        ItemStack switchedTo = leadingSwitch != null ? player.getInventory().getItem(leadingSwitch.getSlot()) : null;
        if (!stoppedItem.isEmpty()) {
            if (switchedTo != null && !ItemStack.isSameItem(switchedTo, stoppedItem)) {
                held = held.withNote("the hotbar switch the client reported with its next tick stopped the item use in this tick");
            } else {
                String difference = "the item use stopped in this tick, which only a hotbar switch explains, and the client reported none with its next tick";
                held = held.withFlag(new Flag(Check.SIMULATION, difference), "differs in: " + difference);
            }
        }
        if (!usedItem.isEmpty() && switchedTo != null && !ItemStack.isSameItem(switchedTo, usedItem)) {
            if (!useStopTried || changesMovementAttributes(switchedTo)) {
                held = held.explainedBy("not simulated: the hotbar switch the client reported with its next tick may have stopped the item use in this tick");
                // - The held tick was the last one to estimate the velocity, and its difference is now unverified -
                if (this.ticksSinceVelocityEstimate == 0) {
                    this.estimatedAfterOutcome = TickOutcome.UNVERIFIED;
                }
            } else {
                held = held.withNote("the item use the hotbar switch reported with the next tick would have stopped does not explain the difference either");
            }
        }
        reports.add(held);
    }

    // - Whether an item in the main hand changes an attribute that may change how the player moves in a tick, which -
    // - an alternative that only stopped the item use leaves out -
    private static boolean changesMovementAttributes(ItemStack item) {
        boolean[] changes = {false};
        item.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (!ATTRIBUTES_BESIDE_MOVEMENT.contains(attribute)) {
                changes[0] = true;
            }
        });
        return changes[0];
    }

    // - The client reports a slot it selected with a hotbar key at the start of its next tick (MultiPlayerGameMode.tick), -
    // - so this tick has to start with that report. Another slot than the sandbox chose confirms the switch as well -
    // - when it steers the vehicle the same way; the client then held another item during the held tick, which in a -
    // - riding tick changes nothing the sandbox compares, only the attack strength ticker afterwards (see -
    // - SandboxPlayer.considerEarlierHotbarSwitch). Without that report, no vanilla client explains the held tick's -
    // - vehicle packets, and the client still holds its slot from before -
    private ClientTickReport confirmInferredHotbarSwitch(ClientTickReport held, InferredHotbarSwitch inferred, SandboxPlayer player) {
        Inventory inventory = player.getInventory();
        List<Packet<?>> actions = this.tickPackets.actions;
        if (!actions.isEmpty() && actions.getFirst() instanceof ServerboundSetCarriedItemPacket carriedItem && Inventory.isHotbarSlot(carriedItem.getSlot())) {
            int reportedSlot = carriedItem.getSlot();
            if (reportedSlot == inferred.slot()) {
                return held;
            }
            if (this.steersAsInferred(player, inferred, reportedSlot)) {
                ItemStack assumedItem = inventory.getItem(inferred.slot());
                inventory.setSelectedSlot(reportedSlot);
                if (!ItemStack.isSameItem(assumedItem, inventory.getItem(reportedSlot))) {
                    player.considerEarlierHotbarSwitch();
                }
                return held.withNote("the client selected hotbar slot " + (reportedSlot + 1) + " in this tick, where the sandbox chose slot " + (inferred.slot() + 1));
            }
        }
        inventory.setSelectedSlot(inferred.previousSlot());
        String rejection = "the client " + (inferred.steers() ? "steered" : "stopped steering")
                + " its vehicle, which only a hotbar switch explains, and reported none with its next tick";
        return held.withFlag(new Flag(Check.BAD_PACKETS, rejection), "rejected: " + rejection);
    }

    // - Whether selecting this slot hands the vehicle of the inferred switch to the client, or takes it away, as the -
    // - inferred slot did. Once the player left that vehicle, the slot has to hold the same item -
    private boolean steersAsInferred(SandboxPlayer player, InferredHotbarSwitch inferred, int slot) {
        Inventory inventory = player.getInventory();
        if (player.getRootVehicle() != inferred.vehicle()) {
            return ItemStack.isSameItem(inventory.getItem(slot), inventory.getItem(inferred.slot()));
        }
        int selectedSlot = inventory.getSelectedSlot();
        inventory.setSelectedSlot(slot);
        boolean steers = inferred.vehicle().isLocalInstanceAuthoritative();
        inventory.setSelectedSlot(selectedSlot);
        return steers == inferred.steers();
    }

    // - The report held back, taken when no further tick can decide on it: the play phase or the connection ended, -
    // - or the simulation of the next tick failed first. The hotbar switch the sandbox assumed for its tick was then -
    // - never checked, for this reason -
    @Nullable ClientTickReport takeHeldReport(String undecidedReason) {
        ClientTickReport held = this.heldReport;
        InferredHotbarSwitch inferredSwitch = this.heldReportInferredSwitch;
        ItemStack stoppedItem = this.heldReportStoppedItem;
        this.heldReport = null;
        this.heldReportUsedItem = ItemStack.EMPTY;
        this.heldReportUseStopTried = false;
        this.heldReportStoppedItem = ItemStack.EMPTY;
        this.heldReportInferredSwitch = null;
        if (held != null && (inferredSwitch != null || !stoppedItem.isEmpty())) {
            return held.explainedBy("not simulated: " + undecidedReason);
        }
        return held;
    }

    // - A hotbar key the tick's packets showed the client pressing (see inferHotbarSwitch): the slot selected before, -
    // - the one the sandbox selected, the vehicle, and whether the client steers it since -
    private record InferredHotbarSwitch(int previousSlot, int slot, Entity vehicle, boolean steers) {
    }

    // - What the player's tick starts from (see saveTickStart) -
    private record TickStart(
            List<Object> roots, StateSnapshot snapshot, boolean predictedAbilitiesSent, boolean predictedFallFlyingStart,
            OptionalInt predictedRidingJump, List<EntityMotion> pushable
    ) {
    }

    // - An entity's velocity and sync flag, which Entity.push changes when the player pushes it -
    private record EntityMotion(Entity entity, Vec3 deltaMovement, boolean needsSync) {
    }

    // - What trying the tick's alternatives at the player's tick found -
    private sealed interface AlternativeResult {

        // - The tick matched as simulated and needed none of them -
        record SimulatedMatched() implements AlternativeResult {
        }

        // - This combination of alternatives matched what the client reported -
        record Matched(List<TickAlternative> combination) implements AlternativeResult {
        }

        // - Every combination was tried and none matched -
        record NoneMatched() implements AlternativeResult {
        }

        // - They could not be tried, for this reason -
        record Untried(String reason) implements AlternativeResult {
        }
    }

    // - Tells the level's entity storage that the player moved, after its fields were restored behind its back -
    private static void notifyMoved(SandboxPlayer player) throws StateSnapshot.SnapshotException {
        try {
            ((EntityInLevelCallback) LEVEL_CALLBACK.get(player)).onMove();
        } catch (IllegalAccessException exception) {
            throw new StateSnapshot.SnapshotException("cannot reach the player's level callback: " + exception);
        }
    }

    private static Field levelCallbackField() {
        try {
            Field field = Entity.class.getDeclaredField("levelCallback");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException exception) {
            throw new IllegalStateException("Entity.levelCallback is missing in this Minecraft version", exception);
        }
    }

    // - Replays Minecraft.handleKeybinds and MultiPlayerGameMode.tick for this tick from the packets they sent. An -
    // - action the sandbox's vanilla code cannot perform is rejected, and the others go on -
    private void performTickActions(SandboxLevel level, SandboxPlayer player) {
        List<Packet<?>> actions = this.tickPackets.actions;
        if (actions.isEmpty()) {
            return;
        }
        ServerboundSetCarriedItemPacket leadingSwitch = this.leadingHotbarSwitch(player);
        // - Minecraft.pick runs before the key handling, so later actions of the tick do not change what it found -
        Entity camera = this.cameraEntity != null ? this.cameraEntity : player;
        HitResult crosshair = player.raycastHitResult(TICK_PARTIAL_TICK, camera);
        boolean swingAccompanied = false;
        for (int index = 0; index < actions.size(); index++) {
            Packet<?> action = actions.get(index);
            try {
                swingAccompanied = this.performTickAction(action, index, leadingSwitch, crosshair, swingAccompanied, level, player);
            } catch (RuntimeException problem) {
                this.problemLog.log("rejected " + action.type() + " replayed at the end of a client tick", problem);
                this.tickPackets.reject(Check.BAD_PACKETS, action.type() + " could not be performed (" + problem + ")");
            }
        }
    }

    // - Performs one action of the tick; returns whether a swing that follows is part of an attack or a block action -
    private boolean performTickAction(
            Packet<?> action,
            int index,
            @Nullable ServerboundSetCarriedItemPacket leadingSwitch,
            HitResult crosshair,
            boolean swingAccompanied,
            SandboxLevel level,
            SandboxPlayer player
    ) {
        List<Packet<?>> actions = this.tickPackets.actions;
        switch (action) {
            case ServerboundSetCarriedItemPacket carriedItem -> this.selectHotbarSlot(player, carriedItem.getSlot(), carriedItem == leadingSwitch);
            case ServerboundPlayerActionPacket playerAction -> {
                Packet<?> next = index + 1 < actions.size() ? actions.get(index + 1) : null;
                return this.performPlayerAction(playerAction, next, level, player) || swingAccompanied;
            }
            case ServerboundUseItemOnPacket useItemOn -> {
                // - A placed block faces by the player's rotation -
                this.checkActionRotation(player, "used an item on a block");
                this.gameMode.useItemOn(level, player, useItemOn.hand(), useItemOn.hitResult(), useItemOn.sequence(), this.tickPackets);
            }
            case ServerboundUseItemPacket useItem -> {
                player.setYRot(useItem.yRot());
                player.setXRot(useItem.xRot());
                this.gameMode.useItem(level, player, useItem.hand(), useItem.sequence(), this.tickPackets);
            }
            case ServerboundAttackPacket attack -> {
                Entity target = level.getEntity(attack.entityId());
                if (target == null) {
                    this.attackUnknownEntity(player, this.onlySwingsFollow(index));
                } else {
                    this.attackEntity(level, player, target, this.onlySwingsFollow(index));
                }
                return true;
            }
            case ServerboundInteractPacket interact -> {
                Entity target = level.getEntity(interact.entityId());
                if (target == null) {
                    // - What interacting does on the client never moves the player, but it may use up or fill the -
                    // - held item -
                    this.tickPackets.notes.add("interacted with entity " + interact.entityId() + ", which the sandbox does not know");
                    this.markInventoryUnknown(InventoryMenu.CONTAINER_ID);
                } else {
                    this.gameMode.interact(player, target, interact.hand(), interact.location());
                }
            }
            case ServerboundPunchPacket ignored -> {
                if (!swingAccompanied) {
                    // - What the crosshair pointed at depends on the player's rotation -
                    this.checkActionRotation(player, "swung at what the crosshair pointed at");
                    this.gameMode.swingAlone(level, player, crosshair);
                }
                return false;
            }
            default -> throw new IllegalArgumentException("No handler for the action " + action.type());
        }
        return swingAccompanied;
    }

    // - Whether nothing after this action of the tick changes the player: only swings follow, which belong to it or -
    // - only reset the attack strength ticker -
    private boolean onlySwingsFollow(int index) {
        List<Packet<?>> actions = this.tickPackets.actions;
        for (int later = index + 1; later < actions.size(); later++) {
            if (!(actions.get(later) instanceof ServerboundPunchPacket)) {
                return false;
            }
        }
        return true;
    }

    // - MultiPlayerGameMode.attack on an entity the sandbox knows. The client's player may have held its main hand -
    // - item one tick longer (see SandboxPlayer.considerEarlierHotbarSwitch); with the attack strength that left, the -
    // - attack may have slowed the player down where the sandbox's did not, or the other way round. That attack is -
    // - the alternative, made again with the other ticker from a snapshot of the player taken before the attack. It -
    // - needs the attack to be the last thing of the tick that changes the player -
    private void attackEntity(SandboxLevel level, SandboxPlayer player, Entity target, boolean lastChange) {
        StateSnapshot beforeAttack = null;
        if (lastChange && player.isSprinting() && player.hasAlternativeAttackStrengths() && this.alternativesDisabled == null) {
            try {
                beforeAttack = this.captureState(List.of(player, level.getRandom()));
            } catch (StateSnapshot.SnapshotException problem) {
                this.disableAlternatives("the player's state before an attack could not be saved", problem);
            }
        }
        this.gameMode.attack(player, target);
        OptionalInt otherTicker = player.tickerForOtherAttackSlowdown();
        if (otherTicker.isEmpty()) {
            return;
        }
        String uncertainty = "attack strength depends on when the client switched its hotbar slot";
        if (beforeAttack == null) {
            this.tickPackets.uncertainties.add(uncertainty);
            return;
        }
        StateSnapshot savedBeforeAttack = beforeAttack;
        int ticker = otherTicker.getAsInt();
        this.tickPackets.alternatives.add(new TickAlternative(uncertainty, "the attack strength a hotbar switch in the previous tick left", ItemStack.EMPTY,
                attacking -> {
                    this.restoreState(savedBeforeAttack);
                    attacking.useAttackStrengthTicker(ticker);
                    this.gameMode.attack(attacking, target);
                }));
    }

    // - MultiPlayerGameMode.attack on an entity the client knows but the sandbox does not, which no vanilla client -
    // - does. Player.attack then did to the player what the entity allowed: an attack that hurt it with a positive -
    // - knockback slowed the player down, which is the alternative to it doing nothing -
    private void attackUnknownEntity(SandboxPlayer player, boolean lastChange) {
        boolean couldSlowDown = player.attackCouldSlowDown();
        this.gameMode.finishAttack(player);
        this.tickPackets.notes.add("attacked an entity the sandbox does not know");
        if (!couldSlowDown) {
            return;
        }
        String uncertainty = "attacked an entity the client knows but the sandbox does not";
        if (lastChange) {
            this.tickPackets.alternatives.add(new TickAlternative(uncertainty, "the attack on the unknown entity slowing the player down", ItemStack.EMPTY,
                    SandboxPlayer::slowDownAfterAttack));
        } else {
            this.tickPackets.uncertainties.add(uncertainty);
        }
    }

    // - The server ignores a slot outside the hotbar, which a vanilla client never selects -
    private void selectHotbarSlot(SandboxPlayer player, int slot, boolean mayHaveChangedEarlier) {
        if (!Inventory.isHotbarSlot(slot)) {
            this.tickPackets.reject(Check.BAD_PACKETS, "the client selected hotbar slot " + slot + ", which does not exist");
            return;
        }
        player.getInventory().setSelectedSlot(slot);
        if (mayHaveChangedEarlier) {
            player.considerEarlierHotbarSwitch();
        }
    }

    // - Returns whether the action belongs to a swing, which then needs no further interpretation -
    private boolean performPlayerAction(ServerboundPlayerActionPacket action, @Nullable Packet<?> next, SandboxLevel level, SandboxPlayer player) {
        switch (action.getAction()) {
            case START_DESTROY_BLOCK -> {
                this.gameMode.startDestroyBlock(level, player, action.getPos(), action.getSequence(), this.tickPackets);
                return true;
            }
            case STOP_DESTROY_BLOCK -> {
                this.gameMode.finishDestroyBlock(level, player, action.getPos(), action.getSequence(), this.tickPackets);
                return true;
            }
            case ABORT_DESTROY_BLOCK -> {
                boolean switchingTarget = next instanceof ServerboundPlayerActionPacket nextAction
                        && nextAction.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK;
                this.gameMode.abortDestroyBlock(player, switchingTarget);
                return false;
            }
            case CHANGE_DESTROY_DIRECTION, SWAP_ITEM_WITH_OFFHAND -> {
                // - Mining goes on, and the swing that follows adds its progress; the server swaps the hands and -
                // - reports both slots -
                return false;
            }
            case DROP_ITEM -> {
                this.gameMode.dropItem(player, false);
                return false;
            }
            case DROP_ALL_ITEMS -> {
                this.gameMode.dropItem(player, true);
                return false;
            }
            case RELEASE_USE_ITEM -> {
                this.gameMode.releaseUsingItem(player);
                return false;
            }
            case STAB -> {
                this.gameMode.piercingAttack(player);
                return false;
            }
        }
        throw new IllegalArgumentException("Unknown player action " + action.getAction());
    }

    private void collectOngoingUncertainties(List<String> uncertainties) {
        if (this.unknownInventoryMenu.isPresent()) {
            uncertainties.add("items differ from the client's");
        }
    }

    // - A tick the client did not simulate its player in; a rejected packet still makes it MISMATCHED -
    private ClientTickReport notSimulated(long clientTick, SandboxPlayer tickPlayer, boolean reportedSprinting, List<String> notes) {
        ReportedState reported = this.reportedState();
        Set<Flag> rejections = this.tickPackets.rejections;
        if (!rejections.isEmpty()) {
            notes.add(ClientTickPackets.rejectionNote(rejections));
        }
        return new ClientTickReport(
                clientTick, rejections.isEmpty() ? TickOutcome.NOT_SIMULATED : TickOutcome.MISMATCHED,
                tickPlayer.getX(), tickPlayer.getY(), tickPlayer.getZ(), tickPlayer.onGround(), tickPlayer.horizontalCollision, tickPlayer.isSprinting(),
                reported.positionReported(), reported.x(), reported.y(), reported.z(), reported.onGround(), reported.horizontalCollision(), reportedSprinting,
                Double.NaN, null, List.copyOf(rejections), notes
        );
    }

    // - What the client reported for the tick: the values of its movement packet, or what it sent last when it sent -
    // - none this tick -
    private record ReportedState(boolean positionReported, double x, double y, double z, boolean onGround, boolean horizontalCollision) {
    }

    private ReportedState reportedState() {
        ServerboundMovePlayerPacket movePacket = this.tickPackets.movePacket;
        boolean positionReported = movePacket != null && movePacket.hasPosition();
        return new ReportedState(
                positionReported,
                positionReported ? movePacket.getX(0.0) : this.lastSent.x,
                positionReported ? movePacket.getY(0.0) : this.lastSent.y,
                positionReported ? movePacket.getZ(0.0) : this.lastSent.z,
                movePacket != null ? movePacket.isOnGround() : this.lastSent.onGround,
                movePacket != null ? movePacket.horizontalCollision() : this.lastSent.horizontalCollision
        );
    }

    // - Advances the mirror of LocalPlayer's last sent state with what the client actually sent -
    private void advanceLastSent(ReportedState reported) {
        if (this.isCameraOnPlayer()) {
            if (reported.positionReported()) {
                this.lastSent.x = reported.x();
                this.lastSent.y = reported.y();
                this.lastSent.z = reported.z();
                this.lastSent.positionReminder = 0;
            }
            this.lastSent.onGround = reported.onGround();
            this.lastSent.horizontalCollision = reported.horizontalCollision();
        }
    }

    // - Continues from the client's state after a tick whose result differs from the client's -
    private static void adoptReportedState(SandboxPlayer player, ReportedState reported, boolean reportedSprinting, ClientTickPackets packets) {
        player.setPos(reported.x(), reported.y(), reported.z());
        player.setOnGround(reported.onGround());
        player.horizontalCollision = reported.horizontalCollision();
        player.setSprinting(reportedSprinting);
        if (packets.abilitiesReported) {
            player.getAbilities().flying = packets.reportedFlying;
        }
    }

    // - Mirrors LocalPlayer.sendPosition for the simulated player and compares the result with what the client -
    // - sent. On any difference, the sandbox takes over the client's reported state so that the next tick is -
    // - simulated from where the client really is -
    private ClientTickReport compareWithClient(
            long clientTick, SandboxPlayer tickPlayer, Vec3 positionBeforeTick, boolean reportedSprinting, List<String> uncertainties, List<String> notes
    ) {
        ClientTickPackets packets = this.tickPackets;
        double predictedX = tickPlayer.getX();
        double predictedY = tickPlayer.getY();
        double predictedZ = tickPlayer.getZ();
        boolean predictedOnGround = tickPlayer.onGround();
        boolean predictedHorizontalCollision = tickPlayer.horizontalCollision;
        boolean predictedSprinting = tickPlayer.isSprinting();

        this.lastSent.positionReminder += this.isCameraOnPlayer() ? 1 : 0;
        ReportedState reported = this.reportedState();
        boolean positionReported = reported.positionReported();
        double reportedX = reported.x();
        double reportedY = reported.y();
        double reportedZ = reported.z();
        boolean reportedOnGround = reported.onGround();
        boolean reportedHorizontalCollision = reported.horizontalCollision();
        double offset = Math.sqrt(Mth.lengthSquared(predictedX - reportedX, predictedY - reportedY, predictedZ - reportedZ));
        List<String> differences = this.movementDifferences(tickPlayer, reported, reportedSprinting, this.lastSent.positionReminder);
        boolean positionDiffers = differences.contains(POSITION_DIFFERENCE);
        boolean onlyPositionDiffers = positionDiffers && differences.size() == 1;
        double roundingOfEstimate = VELOCITY_ESTIMATE_ULPS * Math.ulp(Math.max(Math.abs(reportedX), Math.max(Math.abs(reportedY), Math.abs(reportedZ))));
        if (onlyPositionDiffers && this.ticksSinceVelocityEstimate < VELOCITY_ESTIMATE_TICKS && offset <= roundingOfEstimate) {
            uncertainties.add("the rounding of the velocity estimated " + this.ticksSinceVelocityEstimate + " ticks before");
        } else if (onlyPositionDiffers && this.ticksSinceVelocityEstimate == 0 && this.estimatedAfterOutcome == TickOutcome.UNVERIFIED
                && offset < this.estimatedAfterOffset) {
            // - The estimate only scales the difference the previous tick left; where the velocity does not follow -
            // - the movement by a plain scale (a firework pulling a gliding player), the rest of that difference -
            // - dies down over the following ticks. It stays as unverified as the tick it came from -
            uncertainties.add("the velocity estimated after the previous tick's unverified difference");
        }

        this.advanceLastSent(reported);

        Verdict verdict = this.verdict(differences.stream().map(difference -> difference.equals(POSITION_DIFFERENCE)
                ? String.format(Locale.ROOT, "%s %.4g blocks off", POSITION_DIFFERENCE, offset) : difference).toList(), List.of(), uncertainties, notes);
        TickOutcome outcome = verdict.outcome();
        if (!differences.isEmpty()) {
            // - Continue from the client's state -
            if (positionReported) {
                correctHorizontalVelocity(tickPlayer, positionBeforeTick, new Vec3(reportedX, reportedY, reportedZ));
                this.ticksSinceVelocityEstimate = -1;
                this.estimatedAfterOffset = offset;
                this.estimatedAfterOutcome = outcome;
            }
            adoptReportedState(tickPlayer, reported, reportedSprinting, packets);
        }
        this.ticksSinceVelocityEstimate = Math.min(this.ticksSinceVelocityEstimate + 1, VELOCITY_ESTIMATE_TICKS);

        return new ClientTickReport(
                clientTick, outcome,
                predictedX, predictedY, predictedZ, predictedOnGround, predictedHorizontalCollision, predictedSprinting,
                positionReported, reportedX, reportedY, reportedZ, reportedOnGround, reportedHorizontalCollision, reportedSprinting,
                offset, null, verdict.flags(), notes
        );
    }

    // - How the player now differs from what the client reported for the tick on foot: whether LocalPlayer.sendPosition -
    // - had to send a position (with the position reminder counted up for this tick), the position itself, the ground, -
    // - collision and sprinting state, and the packets the player sends while it ticks. Only reads -
    private List<String> movementDifferences(SandboxPlayer player, ReportedState reported, boolean reportedSprinting, int positionReminder) {
        List<String> differences = new ArrayList<>();
        boolean predictedPositionSent = this.isCameraOnPlayer()
                && (Mth.lengthSquared(player.getX() - this.lastSent.x, player.getY() - this.lastSent.y, player.getZ() - this.lastSent.z) > Mth.square(MINIMUM_REPORTED_MOVEMENT)
                || positionReminder >= POSITION_REMINDER_INTERVAL);
        if (predictedPositionSent != reported.positionReported()) {
            differences.add(predictedPositionSent ? "expected a position, none was sent" : "a position was sent, none was expected");
        }
        if (reported.positionReported() && (player.getX() != reported.x() || player.getY() != reported.y() || player.getZ() != reported.z())) {
            differences.add(POSITION_DIFFERENCE);
        }
        if (player.onGround() != reported.onGround()) {
            differences.add("on ground");
        }
        if (player.horizontalCollision != reported.horizontalCollision()) {
            differences.add("horizontal collision");
        }
        if (player.isSprinting() != reportedSprinting) {
            differences.add("sprinting");
        }
        this.addPlayerCommandDifferences(differences, player);
        if (this.tickPackets.vehicleMove != null) {
            differences.add("a vehicle position was sent while not riding");
        }
        return differences;
    }

    // - movementDifferences right after the player's tick, before compareWithClient counts the position reminder up. -
    // - The entities after the player only push it, which changes its velocity, not its position or state -
    private List<String> slotDifferences(SandboxPlayer player) {
        return this.movementDifferences(player, this.reportedState(), this.lastSent.sprinting,
                this.lastSent.positionReminder + (this.isCameraOnPlayer() ? 1 : 0));
    }

    // - What the player itself sends while it ticks, on foot and riding: its abilities (flying), the start of gliding and -
    // - the jump of its vehicle -
    private void addPlayerCommandDifferences(List<String> differences, SandboxPlayer tickPlayer) {
        ClientTickPackets packets = this.tickPackets;
        if (packets.predictedAbilitiesSent != packets.abilitiesReported
                || packets.abilitiesReported && tickPlayer.getAbilities().flying != packets.reportedFlying) {
            differences.add("flying");
        }
        if (packets.predictedFallFlyingStart != packets.fallFlyingStartReported) {
            differences.add("fall flying start");
        }
        if (!packets.predictedRidingJump.equals(packets.reportedRidingJump)) {
            differences.add("riding jump");
        }
    }

    // - The outcome of a compared tick and the checks it failed, from how the player and how its vehicle differ from -
    // - what the client reported, with the notes that explain it. An uncertainty explains the differences, but a -
    // - rejected packet makes the tick MISMATCHED even when everything else matched: no uncertainty explains a packet -
    // - no vanilla client sends -
    private Verdict verdict(List<String> playerDifferences, List<String> vehicleDifferences, List<String> uncertainties, List<String> notes) {
        Set<Flag> rejections = this.tickPackets.rejections;
        List<String> differences = new ArrayList<>(playerDifferences);
        differences.addAll(vehicleDifferences);
        if (differences.isEmpty() && rejections.isEmpty()) {
            if (!uncertainties.isEmpty()) {
                notes.add("matched despite: " + String.join(", ", uncertainties));
            }
            return new Verdict(TickOutcome.MATCHED, List.of());
        }
        if (!differences.isEmpty()) {
            notes.add("differs in: " + String.join(", ", differences));
        }
        if (!rejections.isEmpty()) {
            notes.add(ClientTickPackets.rejectionNote(rejections));
        }
        if (!uncertainties.isEmpty()) {
            notes.add((differences.isEmpty() ? "matched despite: " : "not simulated: ") + String.join(", ", uncertainties));
        }
        List<Flag> flags = new ArrayList<>(rejections);
        if (uncertainties.isEmpty()) {
            if (!playerDifferences.isEmpty()) {
                flags.add(new Flag(Check.SIMULATION, "differs in: " + String.join(", ", playerDifferences)));
            }
            if (!vehicleDifferences.isEmpty()) {
                flags.add(new Flag(Check.VEHICLE, "differs in: " + String.join(", ", vehicleDifferences)));
            }
        }
        return new Verdict(flags.isEmpty() ? TickOutcome.UNVERIFIED : TickOutcome.MISMATCHED, flags);
    }

    // - The outcome of a compared tick and the checks it failed -
    private record Verdict(TickOutcome outcome, List<Flag> flags) {
    }

    // - LocalPlayer.sendChanges while the player rides: a rotation packet every tick with the player's ground and -
    // - collision state, and, when the player steers the vehicle (it is authoritative on the client), the vehicle's -
    // - position (ServerboundMoveVehiclePacket, from Entity.getClientPositionAndRotation) and the player's sprinting. -
    // - The sandbox moved the vehicle with the same vanilla code and the same keys. A vehicle the player does not steer -
    // - moves as the server says, which the sandbox follows like the client. On any difference the sandbox takes over -
    // - what the client reported. vehiclePositionBeforeTick is null when the player changed vehicles during the tick -
    private ClientTickReport compareRiding(
            long clientTick, SandboxPlayer tickPlayer, @Nullable Vec3 vehiclePositionBeforeTick, boolean reportedSprinting, List<String> uncertainties, List<String> notes
    ) {
        ClientTickPackets packets = this.tickPackets;
        Entity vehicle = tickPlayer.getRootVehicle();
        String vehicleType = vehicle.typeHolder().getRegisteredName();
        boolean steering = vehicle.isLocalInstanceAuthoritative();
        boolean predictedOnGround = tickPlayer.onGround();
        boolean predictedHorizontalCollision = tickPlayer.horizontalCollision;
        boolean predictedSprinting = tickPlayer.isSprinting();
        // - What the player itself sends while riding, and what it sends for its vehicle -
        List<String> playerDifferences = new ArrayList<>();
        List<String> vehicleDifferences = new ArrayList<>();
        ServerboundMovePlayerPacket movePacket = packets.movePacket;
        if (movePacket == null || !movePacket.hasRotation()) {
            playerDifferences.add("expected a rotation, none was sent");
        } else if (movePacket.hasPosition()) {
            playerDifferences.add("a position was sent while riding");
        } else {
            if (movePacket.isOnGround() != predictedOnGround) {
                playerDifferences.add("on ground");
            }
            if (movePacket.horizontalCollision() != predictedHorizontalCollision) {
                playerDifferences.add("horizontal collision");
            }
        }
        // - LocalPlayer.sendIsSprintingIfNeeded only runs for a steered vehicle -
        if (steering && predictedSprinting != reportedSprinting) {
            playerDifferences.add("sprinting");
        }
        this.addPlayerCommandDifferences(playerDifferences, tickPlayer);

        ServerboundMoveVehiclePacket vehicleMove = packets.vehicleMove;
        ClientTickReport.VehicleState vehicleState = null;
        if (steering) {
            PositionAndRotation predicted = vehicle.getClientPositionAndRotation();
            Vec3 predictedPosition = predicted.position();
            boolean predictedVehicleOnGround = vehicle.onGround();
            if (vehicleMove == null) {
                vehicleDifferences.add("expected a vehicle position, none was sent");
                vehicleState = new ClientTickReport.VehicleState(
                        vehicleType, predictedPosition.x, predictedPosition.y, predictedPosition.z, predicted.yRot(), predicted.xRot(), predictedVehicleOnGround,
                        false, Double.NaN, Double.NaN, Double.NaN, Float.NaN, Float.NaN, false, Double.NaN
                );
            } else {
                PositionAndRotation reportedVehicle = vehicleMove.movingTo();
                Vec3 reportedPosition = reportedVehicle.position();
                if (predictedPosition.x != reportedPosition.x || predictedPosition.y != reportedPosition.y || predictedPosition.z != reportedPosition.z) {
                    vehicleDifferences.add(String.format(Locale.ROOT, "vehicle position %.4g blocks off", predictedPosition.distanceTo(reportedPosition)));
                }
                if (predicted.yRot() != reportedVehicle.yRot() || predicted.xRot() != reportedVehicle.xRot()) {
                    vehicleDifferences.add("vehicle rotation");
                }
                if (predictedVehicleOnGround != vehicleMove.onGround()) {
                    vehicleDifferences.add("vehicle on ground");
                }
                vehicleState = new ClientTickReport.VehicleState(
                        vehicleType, predictedPosition.x, predictedPosition.y, predictedPosition.z, predicted.yRot(), predicted.xRot(), predictedVehicleOnGround,
                        true, reportedPosition.x, reportedPosition.y, reportedPosition.z, reportedVehicle.yRot(), reportedVehicle.xRot(), vehicleMove.onGround(),
                        predictedPosition.distanceTo(reportedPosition)
                );
            }
        } else {
            notes.add("riding " + vehicleType + ", which the server moves");
            if (vehicleMove != null) {
                vehicleDifferences.add("a vehicle position was sent for a vehicle the client does not steer");
            }
        }

        Verdict verdict = this.verdict(playerDifferences, vehicleDifferences, uncertainties, notes);
        if (!playerDifferences.isEmpty() || !vehicleDifferences.isEmpty()) {
            // - Continue from the client's state -
            if (steering && vehicleMove != null) {
                PositionAndRotation reportedVehicle = vehicleMove.movingTo();
                Vec3 reportedPosition = reportedVehicle.position();
                if (vehiclePositionBeforeTick != null) {
                    correctHorizontalVelocity(vehicle, vehiclePositionBeforeTick, reportedPosition);
                }
                vehicle.absSnapTo(reportedPosition.x, reportedPosition.y, reportedPosition.z, reportedVehicle.yRot(), reportedVehicle.xRot());
                vehicle.setOnGround(vehicleMove.onGround());
                // - positionRider only has to move the passengers along; a boat turns the player's head a second -
                // - time, and its rotation, which the client reported, is taken below -
                float yHeadRot = tickPlayer.getYHeadRot();
                vehicle.getPassengers().forEach(vehicle::positionRider);
                tickPlayer.setYHeadRot(yHeadRot);
            }
            if (movePacket != null && movePacket.hasRotation() && !movePacket.hasPosition()) {
                tickPlayer.setOnGround(movePacket.isOnGround());
                tickPlayer.horizontalCollision = movePacket.horizontalCollision();
            }
            if (steering) {
                tickPlayer.setSprinting(reportedSprinting);
            }
            if (packets.abilitiesReported) {
                tickPlayer.getAbilities().flying = packets.reportedFlying;
            }
        }
        // - The rotation the client ended its tick with, which it reported. The sandbox's lies a rounding apart after -
        // - a boat's turn (see passengerTurnOfTick), or further when the turn depends on what the client does not -
        // - report (see passengerTurnUnknown). The head keeps the sandbox's turn: Player.aiStep points it along the -
        // - rotation before the vehicle turns both, and AbstractBoat.clampRotation limits only the rotation -
        if (movePacket != null && movePacket.hasRotation()) {
            tickPlayer.setYRot(movePacket.getYRot(tickPlayer.getYRot()));
            tickPlayer.setXRot(movePacket.getXRot(tickPlayer.getXRot()));
        }
        // - A ridden player's own velocity is zeroed every tick (Entity.rideTick), so no estimate of it lasts -
        this.ticksSinceVelocityEstimate = VELOCITY_ESTIMATE_TICKS;

        ReportedState reported = this.reportedState();
        return new ClientTickReport(
                clientTick, verdict.outcome(),
                tickPlayer.getX(), tickPlayer.getY(), tickPlayer.getZ(), predictedOnGround, predictedHorizontalCollision, predictedSprinting,
                reported.positionReported(), reported.x(), reported.y(), reported.z(), reported.onGround(), reported.horizontalCollision(), reportedSprinting,
                Double.NaN, vehicleState, verdict.flags(), notes
        );
    }

    // - The client does not report its velocity, yet a position difference usually comes from a velocity difference -
    // - (a push, an impulse) that carries into the following ticks. On each horizontal axis, the step that turns a -
    // - tick's movement into the velocity for the next tick scales the movement (block friction, fluid drag, or zero -
    // - after a collision). That scale is read from what the simulation itself just did, and the velocity changes by -
    // - the same scale times the part of the client's movement the simulation did not make, so an axis whose -
    // - movement matched keeps its velocity exactly. An axis the simulation did not move along gives no scale and -
    // - keeps its velocity; vertical velocity also depends on gravity, which is not a scale, and is kept -
    private static void correctHorizontalVelocity(Entity entity, Vec3 positionBeforeTick, Vec3 reportedPosition) {
        Vec3 predictedMovement = entity.position().subtract(positionBeforeTick);
        Vec3 reportedMovement = reportedPosition.subtract(positionBeforeTick);
        Vec3 velocity = entity.getDeltaMovement();
        entity.setDeltaMovement(
                velocityForMovement(velocity.x, predictedMovement.x, reportedMovement.x),
                velocity.y,
                velocityForMovement(velocity.z, predictedMovement.z, reportedMovement.z)
        );
    }

    private static double velocityForMovement(double predictedVelocity, double predictedMovement, double reportedMovement) {
        if (predictedMovement == 0.0) {
            return predictedVelocity;
        }
        return predictedVelocity + (reportedMovement - predictedMovement) * (predictedVelocity / predictedMovement);
    }

    // - ServerboundPlayerInputPacket: LocalPlayer only sends its keys when they change -
    void onInputReported(Input input) {
        this.lastSent.input = input;
    }

    // - START_SPRINTING and STOP_SPRINTING: LocalPlayer only sends its sprinting state when it changes -
    void onSprintReported(boolean sprinting) {
        this.lastSent.sprinting = sprinting;
        if (sprinting) {
            this.tickPackets.sprintStartReported = true;
        }
    }

    // - ServerboundPlayerAbilitiesPacket, sent while the player ticks -
    void onAbilitiesReported(boolean flying) {
        this.tickPackets.abilitiesReported = true;
        this.tickPackets.reportedFlying = flying;
    }

    // - START_FALL_FLYING, sent while the player ticks -
    void onFallFlyingStartReported() {
        this.tickPackets.fallFlyingStartReported = true;
    }

    // - START_RIDING_JUMP, sent while the player ticks, with the jump power -
    void onRidingJumpReported(int jumpPower) {
        this.tickPackets.reportedRidingJump = OptionalInt.of(jumpPower);
    }

    // - LocalPlayer's record of what it last sent: xLast, yLast, zLast, lastOnGround, lastHorizontalCollision, -
    // - positionReminder, wasSprinting and lastSentInput. A new LocalPlayer starts with zeros -
    private static final class LastSentState {
        private double x;
        private double y;
        private double z;
        private boolean onGround;
        private boolean horizontalCollision;
        private int positionReminder;
        private boolean sprinting;
        private Input input = Input.EMPTY;

        private void reset(Input lastSentInput, boolean wasSprinting) {
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
            this.onGround = false;
            this.horizontalCollision = false;
            this.positionReminder = 0;
            this.sprinting = wasSprinting;
            this.input = lastSentInput;
        }
    }
}
