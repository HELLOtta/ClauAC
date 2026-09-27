package io.github.hellotta.clauac.simulation.session;

import com.google.common.hash.HashCode;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.TickOutcome;
import io.github.hellotta.clauac.simulation.player.ClientContext;
import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import io.github.hellotta.clauac.simulation.player.SandboxPlayerInfo;
import io.github.hellotta.clauac.simulation.registry.ReceivedRegistries;
import io.github.hellotta.clauac.simulation.world.SandboxClockManager;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import io.github.hellotta.clauac.simulation.world.SandboxLevelData;
import io.github.hellotta.clauac.simulation.world.SandboxRecipeContainer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
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
import net.minecraft.world.level.storage.TagValueInput;
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
    private int ticksSinceVelocityEstimate = VELOCITY_ESTIMATE_TICKS;
    // - The difference and the outcome of the tick whose resync estimated the velocity last -
    private double estimatedAfterOffset;
    private TickOutcome estimatedAfterOutcome = TickOutcome.MATCHED;
    private final ClientTickPackets tickPackets = new ClientTickPackets();
    private final LastSentState lastSent = new LastSentState();

    PlayConnection(
            GameProfile localGameProfile, ReceivedRegistries registries, FeatureFlagSet enabledFeatures, Runnable inventoryResyncRequest, ProblemLog problemLog
    ) {
        this.localGameProfile = localGameProfile;
        this.registries = registries;
        this.enabledFeatures = enabledFeatures;
        this.inventoryResyncRequest = inventoryResyncRequest;
        this.problemLog = problemLog;
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
        if (current.getX() != answer.x() || current.getY() != answer.y() || current.getZ() != answer.z()
                || current.getYRot() != answer.yRot() || current.getXRot() != answer.xRot()) {
            this.tickPackets.uncertainties.add("teleport result differed");
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
            reports.add(this.failedTick(clientTick, problem));
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
            this.tickPackets.rejections.add(levelTickFailure);
        }
        return reports;
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
        List<String> uncertainties = new ArrayList<>(this.tickPackets.uncertainties);
        this.collectOngoingUncertainties(uncertainties);
        // - LivingEntity.updatingUsingItem, at the start of the player's tick, stops the use once the used hand holds -
        // - another item -
        ItemStack usedMainHandItem = tickPlayer.isUsingItem() && tickPlayer.getUsedItemHand() == InteractionHand.MAIN_HAND
                ? tickPlayer.getUseItem().copy()
                : ItemStack.EMPTY;
        Vec3 positionBeforeTick = tickPlayer.position();
        Entity vehicleBeforeTick = tickPlayer.getRootVehicle();
        Vec3 vehiclePositionBeforeTick = vehicleBeforeTick.position();
        tickLevel.tickEntities();
        tickLevel.tickBlockEntities();
        ClientTickReport report;
        ItemStack heldUsedItem = ItemStack.EMPTY;
        // - LocalPlayer.sendChanges decides after the tick whether the player rides -
        if (tickPlayer.isPassenger()) {
            Vec3 steeredVehicleBeforeTick = tickPlayer.getRootVehicle() == vehicleBeforeTick ? vehiclePositionBeforeTick : null;
            report = this.compareRiding(clientTick, tickPlayer, steeredVehicleBeforeTick, reportedSprinting, uncertainties, new ArrayList<>(this.tickPackets.notes));
        } else {
            report = this.compareWithClient(clientTick, tickPlayer, positionBeforeTick, reportedSprinting, uncertainties, new ArrayList<>(this.tickPackets.notes));
            if (report.outcome() == TickOutcome.MISMATCHED && this.tickPackets.rejections.isEmpty()) {
                heldUsedItem = usedMainHandItem;
            }
        }
        if (inferredSwitch != null || !heldUsedItem.isEmpty()) {
            this.heldReport = report;
            this.heldReportUsedItem = heldUsedItem;
            this.heldReportInferredSwitch = inferredSwitch;
            return null;
        }
        return report;
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

    // - A tick whose simulation failed partway. Nothing the client sent can be checked any more, so the tick is -
    // - MISMATCHED, and the sandbox takes over what the client reported, as after a mismatch, so that the next tick -
    // - starts from where the client is -
    private ClientTickReport failedTick(long clientTick, RuntimeException problem) {
        ClientTickPackets packets = this.tickPackets;
        ReportedState reported = this.reportedState();
        boolean reportedSprinting = this.lastSent.sprinting;
        List<String> rejections = new ArrayList<>(packets.rejections);
        rejections.add("the simulation of this tick failed (" + problem + ")");
        List<String> notes = new ArrayList<>(packets.notes);
        notes.add("rejected: " + String.join(", ", rejections));
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
                Double.NaN, null, notes
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
    // - sandbox inferred for that tick has to be reported now. A held mismatch may come from a hotbar switch that -
    // - stopped the player's item use during that tick: if this tick reports such a switch, the held tick is -
    // - unverified; otherwise its mismatch stands -
    private void releaseHeldReport(SandboxPlayer player, List<ClientTickReport> reports) {
        ClientTickReport held = this.heldReport;
        if (held == null) {
            return;
        }
        this.heldReport = null;
        ItemStack usedItem = this.heldReportUsedItem;
        this.heldReportUsedItem = ItemStack.EMPTY;
        InferredHotbarSwitch inferredSwitch = this.heldReportInferredSwitch;
        this.heldReportInferredSwitch = null;
        if (inferredSwitch != null) {
            held = this.confirmInferredHotbarSwitch(held, inferredSwitch, player);
        }
        ServerboundSetCarriedItemPacket leadingSwitch = this.leadingHotbarSwitch(player);
        if (!usedItem.isEmpty() && leadingSwitch != null && !ItemStack.isSameItem(player.getInventory().getItem(leadingSwitch.getSlot()), usedItem)) {
            held = held.withOutcome(TickOutcome.UNVERIFIED,
                    "not simulated: the hotbar switch the client reported with its next tick may have stopped the item use in this tick");
            // - The held tick was the last one to estimate the velocity, and its difference is now unverified -
            if (this.ticksSinceVelocityEstimate == 0) {
                this.estimatedAfterOutcome = TickOutcome.UNVERIFIED;
            }
        }
        reports.add(held);
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
                return held.withOutcome(held.outcome(),
                        "the client selected hotbar slot " + (reportedSlot + 1) + " in this tick, where the sandbox chose slot " + (inferred.slot() + 1));
            }
        }
        inventory.setSelectedSlot(inferred.previousSlot());
        return held.withOutcome(TickOutcome.MISMATCHED, "rejected: the client " + (inferred.steers() ? "steered" : "stopped steering")
                + " its vehicle, which only a hotbar switch explains, and reported none with its next tick");
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

    // - The report held back when the play phase or the connection ends, when no further tick can explain it. A -
    // - hotbar switch the sandbox inferred for its tick was never reported -
    @Nullable ClientTickReport takeHeldReport() {
        ClientTickReport held = this.heldReport;
        InferredHotbarSwitch inferredSwitch = this.heldReportInferredSwitch;
        this.heldReport = null;
        this.heldReportUsedItem = ItemStack.EMPTY;
        this.heldReportInferredSwitch = null;
        if (held != null && inferredSwitch != null) {
            return held.withOutcome(TickOutcome.UNVERIFIED,
                    "not simulated: the play phase ended before the client reported the hotbar slot inferred for this tick");
        }
        return held;
    }

    // - A hotbar key the tick's packets showed the client pressing (see inferHotbarSwitch): the slot selected before, -
    // - the one the sandbox selected, the vehicle, and whether the client steers it since -
    private record InferredHotbarSwitch(int previousSlot, int slot, Entity vehicle, boolean steers) {
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
                this.tickPackets.rejections.add(action.type() + " could not be performed (" + problem + ")");
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
                    this.tickPackets.uncertainties.add("attacked an entity the client knows but the sandbox does not");
                } else {
                    if (player.attackDependsOnHotbarSwitchTiming()) {
                        this.tickPackets.uncertainties.add("attack strength depends on when the client switched its hotbar slot");
                    }
                    this.gameMode.attack(player, target);
                }
                return true;
            }
            case ServerboundInteractPacket interact -> {
                Entity target = level.getEntity(interact.entityId());
                if (target == null) {
                    this.tickPackets.uncertainties.add("interacted with an entity the client knows but the sandbox does not");
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

    // - The server ignores a slot outside the hotbar, which a vanilla client never selects -
    private void selectHotbarSlot(SandboxPlayer player, int slot, boolean mayHaveChangedEarlier) {
        if (!Inventory.isHotbarSlot(slot)) {
            this.tickPackets.rejections.add("the client selected hotbar slot " + slot + ", which does not exist");
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
        Set<String> rejections = this.tickPackets.rejections;
        if (!rejections.isEmpty()) {
            notes.add("rejected: " + String.join(", ", rejections));
        }
        return new ClientTickReport(
                clientTick, rejections.isEmpty() ? TickOutcome.NOT_SIMULATED : TickOutcome.MISMATCHED,
                tickPlayer.getX(), tickPlayer.getY(), tickPlayer.getZ(), tickPlayer.onGround(), tickPlayer.horizontalCollision, tickPlayer.isSprinting(),
                reported.positionReported(), reported.x(), reported.y(), reported.z(), reported.onGround(), reported.horizontalCollision(), reportedSprinting,
                Double.NaN, null, notes
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
        List<String> differences = new ArrayList<>();

        boolean sendsMovement = this.isCameraOnPlayer();
        this.lastSent.positionReminder += sendsMovement ? 1 : 0;
        boolean predictedPositionSent = sendsMovement
                && (Mth.lengthSquared(predictedX - this.lastSent.x, predictedY - this.lastSent.y, predictedZ - this.lastSent.z) > Mth.square(MINIMUM_REPORTED_MOVEMENT)
                || this.lastSent.positionReminder >= POSITION_REMINDER_INTERVAL);
        ReportedState reported = this.reportedState();
        boolean positionReported = reported.positionReported();
        double reportedX = reported.x();
        double reportedY = reported.y();
        double reportedZ = reported.z();
        boolean reportedOnGround = reported.onGround();
        boolean reportedHorizontalCollision = reported.horizontalCollision();
        double offset = Math.sqrt(Mth.lengthSquared(predictedX - reportedX, predictedY - reportedY, predictedZ - reportedZ));

        if (predictedPositionSent != positionReported) {
            differences.add(predictedPositionSent ? "expected a position, none was sent" : "a position was sent, none was expected");
        }
        boolean positionDiffers = positionReported && (predictedX != reportedX || predictedY != reportedY || predictedZ != reportedZ);
        if (positionDiffers) {
            differences.add("position");
        }
        if (predictedOnGround != reportedOnGround) {
            differences.add("on ground");
        }
        if (predictedHorizontalCollision != reportedHorizontalCollision) {
            differences.add("horizontal collision");
        }
        if (predictedSprinting != reportedSprinting) {
            differences.add("sprinting");
        }
        this.addPlayerCommandDifferences(differences, tickPlayer);
        if (packets.vehicleMove != null) {
            differences.add("a vehicle position was sent while not riding");
        }
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

        TickOutcome outcome = this.outcome(differences, uncertainties, notes);
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
                offset, null, notes
        );
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

    // - The outcome of a compared tick, with the notes that explain it. A rejected packet makes the tick MISMATCHED -
    // - even when everything else matched: no uncertainty explains a packet no vanilla client sends -
    private TickOutcome outcome(List<String> differences, List<String> uncertainties, List<String> notes) {
        Set<String> rejections = this.tickPackets.rejections;
        boolean rejected = !rejections.isEmpty();
        if (differences.isEmpty() && !rejected) {
            if (!uncertainties.isEmpty()) {
                notes.add("matched despite: " + String.join(", ", uncertainties));
            }
            return TickOutcome.MATCHED;
        }
        if (!differences.isEmpty()) {
            notes.add("differs in: " + String.join(", ", differences));
        }
        if (rejected) {
            notes.add("rejected: " + String.join(", ", rejections));
        }
        if (!uncertainties.isEmpty()) {
            notes.add((differences.isEmpty() ? "matched despite: " : "not simulated: ") + String.join(", ", uncertainties));
        }
        return uncertainties.isEmpty() || rejected ? TickOutcome.MISMATCHED : TickOutcome.UNVERIFIED;
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
        List<String> differences = new ArrayList<>();
        ServerboundMovePlayerPacket movePacket = packets.movePacket;
        if (movePacket == null || !movePacket.hasRotation()) {
            differences.add("expected a rotation, none was sent");
        } else if (movePacket.hasPosition()) {
            differences.add("a position was sent while riding");
        } else {
            if (movePacket.isOnGround() != predictedOnGround) {
                differences.add("on ground");
            }
            if (movePacket.horizontalCollision() != predictedHorizontalCollision) {
                differences.add("horizontal collision");
            }
        }
        // - LocalPlayer.sendIsSprintingIfNeeded only runs for a steered vehicle -
        if (steering && predictedSprinting != reportedSprinting) {
            differences.add("sprinting");
        }
        this.addPlayerCommandDifferences(differences, tickPlayer);

        ServerboundMoveVehiclePacket vehicleMove = packets.vehicleMove;
        ClientTickReport.VehicleState vehicleState = null;
        if (steering) {
            PositionAndRotation predicted = vehicle.getClientPositionAndRotation();
            Vec3 predictedPosition = predicted.position();
            boolean predictedVehicleOnGround = vehicle.onGround();
            if (vehicleMove == null) {
                differences.add("expected a vehicle position, none was sent");
                vehicleState = new ClientTickReport.VehicleState(
                        vehicleType, predictedPosition.x, predictedPosition.y, predictedPosition.z, predicted.yRot(), predicted.xRot(), predictedVehicleOnGround,
                        false, Double.NaN, Double.NaN, Double.NaN, Float.NaN, Float.NaN, false, Double.NaN
                );
            } else {
                PositionAndRotation reportedVehicle = vehicleMove.movingTo();
                Vec3 reportedPosition = reportedVehicle.position();
                if (predictedPosition.x != reportedPosition.x || predictedPosition.y != reportedPosition.y || predictedPosition.z != reportedPosition.z) {
                    differences.add("vehicle position");
                }
                if (predicted.yRot() != reportedVehicle.yRot() || predicted.xRot() != reportedVehicle.xRot()) {
                    differences.add("vehicle rotation");
                }
                if (predictedVehicleOnGround != vehicleMove.onGround()) {
                    differences.add("vehicle on ground");
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
                differences.add("a vehicle position was sent for a vehicle the client does not steer");
            }
        }

        TickOutcome outcome = this.outcome(differences, uncertainties, notes);
        if (!differences.isEmpty()) {
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
                clientTick, outcome,
                tickPlayer.getX(), tickPlayer.getY(), tickPlayer.getZ(), predictedOnGround, predictedHorizontalCollision, predictedSprinting,
                reported.positionReported(), reported.x(), reported.y(), reported.z(), reported.onGround(), reported.horizontalCollision(), reportedSprinting,
                Double.NaN, vehicleState, notes
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
