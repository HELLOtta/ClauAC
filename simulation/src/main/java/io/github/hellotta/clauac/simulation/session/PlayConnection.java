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
    // - The client's position is not simulated while riding; the first tick after riding adopts the reported one -
    private boolean positionNeedsResync;
    // - A checked container click showed that the sandbox's items differ from the client's, in the menu with this -
    // - id. The sandbox's items are unknown until the server sends that menu's or the inventory's full contents -
    private OptionalInt unknownInventoryMenu = OptionalInt.empty();
    // - A MISMATCHED report held back until the client's next tick shows whether a hotbar switch explains it (see -
    // - releaseHeldReport), and the main hand item the player was using during that tick -
    private @Nullable ClientTickReport heldReport;
    private ItemStack heldReportUsedItem = ItemStack.EMPTY;
    private int ticksSinceVelocityEstimate = VELOCITY_ESTIMATE_TICKS;
    // - The difference and the outcome of the tick whose resync estimated the velocity last -
    private double estimatedAfterOffset;
    private TickOutcome estimatedAfterOutcome = TickOutcome.MATCHED;
    private final ClientTickPackets tickPackets = new ClientTickPackets();
    private final LastSentState lastSent = new LastSentState();

    PlayConnection(GameProfile localGameProfile, ReceivedRegistries registries, FeatureFlagSet enabledFeatures, Runnable inventoryResyncRequest) {
        this.localGameProfile = localGameProfile;
        this.registries = registries;
        this.enabledFeatures = enabledFeatures;
        this.inventoryResyncRequest = inventoryResyncRequest;
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
    // - Returns the reports that are final, in the order of the client's ticks: a report may be held back for one tick -
    List<ClientTickReport> tick(long clientTick) {
        Input reportedKeys = this.lastSent.input;
        boolean reportedSprinting = this.lastSent.sprinting;
        SandboxLevel tickLevel = this.level;
        SandboxPlayer tickPlayer = this.player;
        if (tickLevel == null || tickPlayer == null) {
            throw new IllegalStateException("The client ended a tick before it joined a level");
        }
        List<ClientTickReport> reports = new ArrayList<>(2);
        this.releaseHeldReport(tickPlayer, reports);
        tickLevel.tickRateManager().tick();
        // - The rotation of the whole tick, which the client's key and mouse actions already used -
        ServerboundMovePlayerPacket movePacket = this.tickPackets.movePacket;
        if (movePacket != null && movePacket.hasRotation()) {
            tickPlayer.setYRot(movePacket.getYRot(tickPlayer.getYRot()));
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
            reports.add(this.notSimulated(clientTick, tickPlayer, reportedSprinting, notes));
        } else if (tickPlayer.isPassenger()) {
            tickLevel.tickEntities();
            tickLevel.tickBlockEntities();
            this.followReportedVehicle(tickPlayer);
            this.positionNeedsResync = true;
            List<String> notes = new ArrayList<>(this.tickPackets.notes);
            notes.add("riding " + tickPlayer.getRootVehicle().typeHolder().getRegisteredName());
            reports.add(this.notSimulated(clientTick, tickPlayer, reportedSprinting, notes));
        } else {
            List<String> uncertainties = new ArrayList<>(this.tickPackets.uncertainties);
            this.collectOngoingUncertainties(uncertainties);
            // - LivingEntity.updatingUsingItem, at the start of the player's tick, stops the use once the used hand -
            // - holds another item -
            ItemStack usedMainHandItem = tickPlayer.isUsingItem() && tickPlayer.getUsedItemHand() == InteractionHand.MAIN_HAND
                    ? tickPlayer.getUseItem().copy()
                    : ItemStack.EMPTY;
            Vec3 positionBeforeTick = tickPlayer.position();
            tickLevel.tickEntities();
            tickLevel.tickBlockEntities();
            ClientTickReport report = this.compareWithClient(clientTick, tickPlayer, positionBeforeTick, reportedSprinting, uncertainties, new ArrayList<>(this.tickPackets.notes));
            if (report.outcome() == TickOutcome.MISMATCHED && !usedMainHandItem.isEmpty()) {
                this.heldReport = report;
                this.heldReportUsedItem = usedMainHandItem;
            } else {
                reports.add(report);
            }
        }
        tickLevel.tick();
        this.tickPackets.reset();
        return reports;
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

    // - A held report's tick may have ended with a hotbar switch that stopped the player's item use during that tick. -
    // - If this tick reports such a switch, the held tick is unverified; otherwise its mismatch stands -
    private void releaseHeldReport(SandboxPlayer player, List<ClientTickReport> reports) {
        ClientTickReport held = this.heldReport;
        if (held == null) {
            return;
        }
        this.heldReport = null;
        ItemStack usedItem = this.heldReportUsedItem;
        this.heldReportUsedItem = ItemStack.EMPTY;
        ServerboundSetCarriedItemPacket leadingSwitch = this.leadingHotbarSwitch(player);
        if (leadingSwitch != null && !ItemStack.isSameItem(player.getInventory().getItem(leadingSwitch.getSlot()), usedItem)) {
            held = held.withOutcome(TickOutcome.UNVERIFIED,
                    "not simulated: the hotbar switch the client reported with its next tick may have stopped the item use in this tick");
            // - The held tick was the last one to estimate the velocity, and its difference is now unverified -
            if (this.ticksSinceVelocityEstimate == 0) {
                this.estimatedAfterOutcome = TickOutcome.UNVERIFIED;
            }
        }
        reports.add(held);
    }

    // - The report held back when the play phase or the connection ends, when no further tick can explain it -
    @Nullable ClientTickReport takeHeldReport() {
        ClientTickReport held = this.heldReport;
        this.heldReport = null;
        this.heldReportUsedItem = ItemStack.EMPTY;
        return held;
    }

    // - Replays Minecraft.handleKeybinds and MultiPlayerGameMode.tick for this tick from the packets they sent -
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
            switch (action) {
                case ServerboundSetCarriedItemPacket carriedItem -> this.selectHotbarSlot(player, carriedItem.getSlot(), carriedItem == leadingSwitch);
                case ServerboundPlayerActionPacket playerAction -> {
                    Packet<?> next = index + 1 < actions.size() ? actions.get(index + 1) : null;
                    swingAccompanied |= this.performPlayerAction(playerAction, next, level, player);
                }
                case ServerboundUseItemOnPacket useItemOn ->
                        this.gameMode.useItemOn(level, player, useItemOn.hand(), useItemOn.hitResult(), useItemOn.sequence(), this.tickPackets);
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
                    swingAccompanied = true;
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
                        this.gameMode.swingAlone(level, player, crosshair);
                    }
                    swingAccompanied = false;
                }
                default -> throw new IllegalArgumentException("No handler for the action " + action.type());
            }
        }
    }

    // - The server ignores a slot outside the hotbar, which a vanilla client never selects -
    private void selectHotbarSlot(SandboxPlayer player, int slot, boolean mayHaveChangedEarlier) {
        if (!Inventory.isHotbarSlot(slot)) {
            this.tickPackets.notes.add("the client selected hotbar slot " + slot + ", which does not exist");
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

    // - While the player steers its vehicle, the client moves the vehicle and reports where it went. The sandbox does -
    // - not compare vehicles, and places the vehicle and its riders where the client reported them -
    private void followReportedVehicle(SandboxPlayer ridingPlayer) {
        ServerboundMoveVehiclePacket vehicleMove = this.tickPackets.vehicleMove;
        Entity vehicle = ridingPlayer.getRootVehicle();
        if (vehicleMove != null && vehicle != ridingPlayer && vehicle.isLocalInstanceAuthoritative()) {
            Vec3 position = vehicleMove.movingTo().position();
            vehicle.absSnapTo(position.x, position.y, position.z, vehicleMove.movingTo().yRot(), vehicleMove.movingTo().xRot());
            vehicle.setOnGround(vehicleMove.onGround());
            vehicle.getPassengers().forEach(vehicle::positionRider);
        }
    }

    private void collectOngoingUncertainties(List<String> uncertainties) {
        if (this.positionNeedsResync) {
            uncertainties.add("position unknown after riding");
        }
        if (this.unknownInventoryMenu.isPresent()) {
            uncertainties.add("items differ from the client's");
        }
    }

    private ClientTickReport notSimulated(long clientTick, SandboxPlayer tickPlayer, boolean reportedSprinting, List<String> notes) {
        ServerboundMovePlayerPacket movePacket = this.tickPackets.movePacket;
        boolean positionReported = movePacket != null && movePacket.hasPosition();
        double reportedX = positionReported ? movePacket.getX(0.0) : this.lastSent.x;
        double reportedY = positionReported ? movePacket.getY(0.0) : this.lastSent.y;
        double reportedZ = positionReported ? movePacket.getZ(0.0) : this.lastSent.z;
        boolean reportedOnGround = movePacket != null ? movePacket.isOnGround() : this.lastSent.onGround;
        boolean reportedHorizontalCollision = movePacket != null ? movePacket.horizontalCollision() : this.lastSent.horizontalCollision;
        return new ClientTickReport(
                clientTick, TickOutcome.NOT_SIMULATED,
                tickPlayer.getX(), tickPlayer.getY(), tickPlayer.getZ(), tickPlayer.onGround(), tickPlayer.horizontalCollision, tickPlayer.isSprinting(),
                positionReported, reportedX, reportedY, reportedZ, reportedOnGround, reportedHorizontalCollision, reportedSprinting,
                Double.NaN, notes
        );
    }

    // - Mirrors LocalPlayer.sendPosition for the simulated player and compares the result with what the client -
    // - sent. On any difference, the sandbox takes over the client's reported state so that the next tick is -
    // - simulated from where the client really is -
    private ClientTickReport compareWithClient(
            long clientTick, SandboxPlayer tickPlayer, Vec3 positionBeforeTick, boolean reportedSprinting, List<String> uncertainties, List<String> notes
    ) {
        ClientTickPackets packets = this.tickPackets;
        ServerboundMovePlayerPacket movePacket = packets.movePacket;
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
        boolean positionReported = movePacket != null && movePacket.hasPosition();
        double reportedX = positionReported ? movePacket.getX(0.0) : this.lastSent.x;
        double reportedY = positionReported ? movePacket.getY(0.0) : this.lastSent.y;
        double reportedZ = positionReported ? movePacket.getZ(0.0) : this.lastSent.z;
        boolean reportedOnGround = movePacket != null ? movePacket.isOnGround() : this.lastSent.onGround;
        boolean reportedHorizontalCollision = movePacket != null ? movePacket.horizontalCollision() : this.lastSent.horizontalCollision;
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
        if (packets.predictedAbilitiesSent != packets.abilitiesReported
                || packets.abilitiesReported && tickPlayer.getAbilities().flying != packets.reportedFlying) {
            differences.add("flying");
        }
        if (packets.predictedFallFlyingStart != packets.fallFlyingStartReported) {
            differences.add("fall flying start");
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

        // - Advance the mirror of LocalPlayer's last sent state with what the client actually sent -
        if (sendsMovement) {
            if (positionReported) {
                this.lastSent.x = reportedX;
                this.lastSent.y = reportedY;
                this.lastSent.z = reportedZ;
                this.lastSent.positionReminder = 0;
            }
            this.lastSent.onGround = reportedOnGround;
            this.lastSent.horizontalCollision = reportedHorizontalCollision;
        }

        TickOutcome outcome;
        if (differences.isEmpty()) {
            outcome = TickOutcome.MATCHED;
            if (!uncertainties.isEmpty()) {
                notes.add("matched despite: " + String.join(", ", uncertainties));
            }
        } else {
            outcome = uncertainties.isEmpty() ? TickOutcome.MISMATCHED : TickOutcome.UNVERIFIED;
            notes.add("differs in: " + String.join(", ", differences));
            if (!uncertainties.isEmpty()) {
                notes.add("not simulated: " + String.join(", ", uncertainties));
            }
            // - Continue from the client's state -
            if (positionReported) {
                correctHorizontalVelocity(tickPlayer, positionBeforeTick, new Vec3(reportedX, reportedY, reportedZ));
                this.ticksSinceVelocityEstimate = -1;
                this.estimatedAfterOffset = offset;
                this.estimatedAfterOutcome = outcome;
            }
            tickPlayer.setPos(reportedX, reportedY, reportedZ);
            tickPlayer.setOnGround(reportedOnGround);
            tickPlayer.horizontalCollision = reportedHorizontalCollision;
            tickPlayer.setSprinting(reportedSprinting);
            if (packets.abilitiesReported) {
                tickPlayer.getAbilities().flying = packets.reportedFlying;
            }
        }
        this.positionNeedsResync = false;
        this.ticksSinceVelocityEstimate = Math.min(this.ticksSinceVelocityEstimate + 1, VELOCITY_ESTIMATE_TICKS);

        return new ClientTickReport(
                clientTick, outcome,
                predictedX, predictedY, predictedZ, predictedOnGround, predictedHorizontalCollision, predictedSprinting,
                positionReported, reportedX, reportedY, reportedZ, reportedOnGround, reportedHorizontalCollision, reportedSprinting,
                offset, notes
        );
    }

    // - The client does not report its velocity, yet a position difference usually comes from a velocity difference -
    // - (a push, an impulse) that carries into the following ticks. On each horizontal axis, the step that turns a -
    // - tick's movement into the velocity for the next tick scales the movement (block friction, fluid drag, or zero -
    // - after a collision). That scale is read from what the simulation itself just did, and the velocity changes by -
    // - the same scale times the part of the client's movement the simulation did not make, so an axis whose -
    // - movement matched keeps its velocity exactly. An axis the simulation did not move along gives no scale and -
    // - keeps its velocity; vertical velocity also depends on gravity, which is not a scale, and is kept -
    private static void correctHorizontalVelocity(SandboxPlayer player, Vec3 positionBeforeTick, Vec3 reportedPosition) {
        Vec3 predictedMovement = player.position().subtract(positionBeforeTick);
        Vec3 reportedMovement = reportedPosition.subtract(positionBeforeTick);
        Vec3 velocity = player.getDeltaMovement();
        player.setDeltaMovement(
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
