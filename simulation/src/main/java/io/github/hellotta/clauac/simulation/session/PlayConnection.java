package io.github.hellotta.clauac.simulation.session;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.TickOutcome;
import io.github.hellotta.clauac.simulation.player.ClientContext;
import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import io.github.hellotta.clauac.simulation.registry.ReceivedRegistries;
import io.github.hellotta.clauac.simulation.world.SandboxClockManager;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import io.github.hellotta.clauac.simulation.world.SandboxLevelData;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.game.ClientboundBlockChangedAckPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundInitializeBorderPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderLerpSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderSizePacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDelayPacket;
import net.minecraft.network.protocol.game.ClientboundSetBorderWarningDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetSimulationDistancePacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.CommonPlayerSpawnInfo;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - Port of what the client-only ClientPacketListener, MultiPlayerGameMode and Minecraft do for the state the local -
// - player moves in, during one play phase of a connection; the client creates a new listener after every -
// - configuration phase. Handlers keep the client's order of operations and leave out what only renders, plays -
// - sounds or shows screens. Other entities are not tracked, so handlers for entity packets only act when the -
// - entity is the local player -
final class PlayConnection implements ClientContext {

    private static final Logger LOGGER = LogUtils.getLogger();
    // - LocalPlayer.POSITION_REMINDER_INTERVAL -
    private static final int POSITION_REMINDER_INTERVAL = 20;
    // - LocalPlayer.sendPosition sends a position once the player moved more than this since the last one -
    private static final double MINIMUM_REPORTED_MOVEMENT = 2.0E-4;

    private final GameProfile localGameProfile;
    private final ReceivedRegistries registries;
    private final FeatureFlagSet enabledFeatures;
    private final SandboxClockManager clockManager = new SandboxClockManager();
    private final Scoreboard scoreboard = new Scoreboard();
    private final Map<UUID, PlayerInfoState> playerInfoMap = new HashMap<>();
    private int serverChunkRadius = 3;
    private int serverSimulationDistance = 3;
    private @Nullable SandboxLevelData levelData;
    private @Nullable SandboxLevel level;
    private @Nullable SandboxPlayer player;
    // - AbstractClientPlayer.playerInfo: looked up once and kept for the lifetime of the player object -
    private @Nullable PlayerInfoState cachedPlayerInfo;
    private boolean clientLoaded;
    // - Minecraft.cameraEntity is the local player -
    private boolean cameraOnPlayer;
    // - MultiPlayerGameMode.localPlayerMode; its previousLocalPlayerMode only serves the game mode switcher screen -
    private GameType localPlayerMode = GameType.DEFAULT_MODE;
    // - The vehicle is not part of the sandbox level, so riding is tracked by entity id only -
    private OptionalInt vehicleId = OptionalInt.empty();
    private OptionalInt removedPlayerVehicleId = OptionalInt.empty();
    // - The client's position is not simulated while riding; the first tick after riding adopts the reported one -
    private boolean positionNeedsResync;
    // - Highest block prediction sequence the client sent for the current level that the server has not -
    // - acknowledged yet; 0 when no prediction is pending. The client's predicted block changes are not simulated -
    private int pendingPredictionSequence;
    private boolean predictionEndedBeforeTick;
    // - Item use is not simulated because the inventory is not tracked. It may be active from a use request until -
    // - the client releases the item or the server reports that the use it had started ended -
    private boolean itemUseMayBeActive;
    private boolean serverConfirmedItemUse;
    // - The client started gliding; not simulated because the equipment is not tracked -
    private boolean fallFlyingMayBeActive;
    private final ClientTickPackets tickPackets = new ClientTickPackets();
    private final LastSentState lastSent = new LastSentState();

    PlayConnection(GameProfile localGameProfile, ReceivedRegistries registries, FeatureFlagSet enabledFeatures) {
        this.localGameProfile = localGameProfile;
        this.registries = registries;
        this.enabledFeatures = enabledFeatures;
    }

    ClientTickPackets tickPackets() {
        return this.tickPackets;
    }

    @Override
    public boolean hasClientLoaded() {
        return this.clientLoaded;
    }

    @Override
    public boolean isCameraOnPlayer() {
        return this.cameraOnPlayer;
    }

    @Override
    public @Nullable GameType playerInfoGameMode() {
        if (this.cachedPlayerInfo == null) {
            this.cachedPlayerInfo = this.playerInfoMap.get(this.localGameProfile.id());
        }
        return this.cachedPlayerInfo != null ? this.cachedPlayerInfo.gameMode : null;
    }

    @Override
    public boolean isLocalModeSpectator() {
        return this.localPlayerMode == GameType.SPECTATOR;
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
            default -> {
                if (this.level == null || this.player == null) {
                    throw new IllegalStateException("Received " + packet.type() + " before the client joined a level");
                }
                this.handleInLevel(packet, this.level, this.player);
            }
        }
    }

    private void handleInLevel(Packet<?> packet, SandboxLevel level, SandboxPlayer player) {
        switch (packet) {
            case ClientboundLevelChunkWithLightPacket chunk ->
                    // - Light data is not applied: lighting is disabled in the sandbox and no movement code reads it -
                    level.getChunkSource().replaceWithPacketData(chunk.x(), chunk.z(), chunk.chunkData());
            case ClientboundForgetLevelChunkPacket forget -> level.getChunkSource().drop(forget.pos());
            case ClientboundChunksBiomesPacket biomes -> this.handleChunksBiomes(biomes, level);
            case ClientboundBlockUpdatePacket blockUpdate -> level.setServerVerifiedBlockState(blockUpdate.getPos(), blockUpdate.getBlockState(), 19);
            case ClientboundSectionBlocksUpdatePacket sectionUpdate ->
                    sectionUpdate.runUpdates((pos, state) -> level.setServerVerifiedBlockState(pos, state, 19));
            case ClientboundBlockChangedAckPacket ack -> this.handleBlockChangedAck(ack);
            case ClientboundSetChunkCacheCenterPacket center -> level.getChunkSource().updateViewCenter(center.getX(), center.getZ());
            case ClientboundSetChunkCacheRadiusPacket radius -> {
                this.serverChunkRadius = radius.getRadius();
                level.getChunkSource().updateViewRadius(radius.getRadius());
            }
            case ClientboundSetSimulationDistancePacket distance -> {
                this.serverSimulationDistance = distance.simulationDistance();
                level.setServerSimulationDistance(this.serverSimulationDistance);
            }
            case ClientboundPlayerPositionPacket position -> this.handleMovePlayer(position, player);
            case ClientboundPlayerRotationPacket rotation -> handleRotatePlayer(rotation, player);
            case ClientboundTeleportEntityPacket teleport -> this.handleTeleportEntity(teleport, level, player);
            case ClientboundPlayerAbilitiesPacket abilities -> handlePlayerAbilities(abilities, player);
            case ClientboundGameEventPacket gameEvent -> this.handleGameEvent(gameEvent, level, player);
            case ClientboundUpdateAttributesPacket attributes -> handleUpdateAttributes(attributes, level);
            case ClientboundSetEntityMotionPacket motion -> {
                Entity entity = level.getEntity(motion.id());
                if (entity != null) {
                    entity.lerpMotion(motion.movement());
                }
            }
            case ClientboundExplodePacket explosion -> explosion.playerKnockback().ifPresent(player::pushFromExplosion);
            case ClientboundUpdateMobEffectPacket effect -> handleUpdateMobEffect(effect, level);
            case ClientboundRemoveMobEffectPacket effect -> {
                if (effect.getEntity(level) instanceof LivingEntity entity) {
                    entity.removeEffectNoUpdate(effect.effect());
                }
            }
            case ClientboundSetEntityDataPacket entityData -> this.handleSetEntityData(entityData, level, player);
            case ClientboundSetHealthPacket health -> {
                player.hurtTo(health.getHealth());
                player.getFoodData().setFoodLevel(health.getFood());
                player.getFoodData().setSaturation(health.getSaturation());
            }
            case ClientboundSetPassengersPacket passengers -> this.handleSetPassengers(passengers, player);
            case ClientboundRemoveEntitiesPacket remove -> this.handleRemoveEntities(remove, level, player);
            case ClientboundSetCameraPacket camera -> this.handleSetCamera(camera, player);
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
                this.enabledFeatures
        );
    }

    // - MultiPlayerGameMode.createPlayer; lastSentInput and wasSprinting are what LocalPlayer's constructor takes -
    private SandboxPlayer createPlayer(SandboxLevel level, Input lastSentInput, boolean wasSprinting) {
        SandboxPlayer created = new SandboxPlayer(level, this.localGameProfile, this);
        this.cachedPlayerInfo = null;
        this.vehicleId = OptionalInt.empty();
        this.lastSent.reset(lastSentInput, wasSprinting);
        return created;
    }

    // - A new ClientLevel comes with a new BlockStatePredictionHandler, which forgets every pending prediction -
    private void onNewLevel(SandboxLevel newLevel) {
        this.level = newLevel;
        this.pendingPredictionSequence = 0;
    }

    // - MultiPlayerGameMode.setLocalMode -
    private void setLocalMode(GameType mode, SandboxPlayer player) {
        this.localPlayerMode = mode;
        this.localPlayerMode.updatePlayerAbilities(player.getAbilities());
    }

    private void handleLogin(ClientboundLoginPacket packet) {
        // - handleLogin creates a new MultiPlayerGameMode -
        this.localPlayerMode = GameType.DEFAULT_MODE;
        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        this.serverChunkRadius = packet.chunkRadius();
        this.serverSimulationDistance = packet.simulationDistance();
        SandboxLevelData newLevelData = new SandboxLevelData(Difficulty.NORMAL, packet.hardcore(), spawnInfo.isFlat());
        this.levelData = newLevelData;
        SandboxLevel newLevel = this.createLevel(newLevelData, spawnInfo);
        this.onNewLevel(newLevel);
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
        this.localPlayerMode.updatePlayerAbilities(loginPlayer.getAbilities());
        this.cameraOnPlayer = true;
        loginPlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        loginPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        this.setLocalMode(spawnInfo.gameType(), loginPlayer);
    }

    private void handleRespawn(ClientboundRespawnPacket packet) {
        SandboxPlayer oldPlayer = this.player;
        SandboxLevelData oldLevelData = this.levelData;
        if (oldPlayer == null || oldLevelData == null || this.level == null) {
            throw new IllegalStateException("Received a respawn before the client joined a level");
        }
        CommonPlayerSpawnInfo spawnInfo = packet.commonPlayerSpawnInfo();
        ResourceKey<Level> dimensionKey = spawnInfo.dimension();
        boolean dimensionChanged = dimensionKey != oldPlayer.level().dimension();
        if (dimensionChanged) {
            SandboxLevelData newLevelData = new SandboxLevelData(oldLevelData.getDifficulty(), oldLevelData.isHardcore(), spawnInfo.isFlat());
            this.levelData = newLevelData;
            this.onNewLevel(this.createLevel(newLevelData, spawnInfo));
        }
        SandboxLevel respawnLevel = this.level;

        this.cameraOnPlayer = false;
        boolean keepEntityData = packet.shouldKeep(ClientboundRespawnPacket.KEEP_ENTITY_DATA);
        SandboxPlayer newPlayer = keepEntityData
                ? this.createPlayer(respawnLevel, this.lastSent.input, oldPlayer.isSprinting())
                : this.createPlayer(respawnLevel, Input.EMPTY, false);

        this.clientLoaded = false;
        newPlayer.setId(oldPlayer.getId());
        this.player = newPlayer;
        this.cameraOnPlayer = true;
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
        this.localPlayerMode.updatePlayerAbilities(newPlayer.getAbilities());
        newPlayer.setLastDeathLocation(spawnInfo.lastDeathLocation());
        newPlayer.setPortalCooldown(spawnInfo.portalCooldown());
        this.setLocalMode(spawnInfo.gameType(), newPlayer);
    }

    private void handleChunksBiomes(ClientboundChunksBiomesPacket packet, SandboxLevel level) {
        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            level.getChunkSource().replaceBiomes(data.pos().x(), data.pos().z(), data.getReadBuffer());
        }

        for (ClientboundChunksBiomesPacket.ChunkBiomeData data : packet.chunkBiomeData()) {
            level.onChunkLoaded(new ChunkPos(data.pos().x(), data.pos().z()));
        }
    }

    private void handleBlockChangedAck(ClientboundBlockChangedAckPacket packet) {
        if (this.pendingPredictionSequence != 0 && packet.sequence() >= this.pendingPredictionSequence) {
            // - Ending predictions can move the client's player out of blocks the server did not place -
            // - (ClientLevel.syncBlockState), before its next tick -
            this.pendingPredictionSequence = 0;
            this.predictionEndedBeforeTick = true;
        }
    }

    private void handleMovePlayer(ClientboundPlayerPositionPacket packet, SandboxPlayer player) {
        if (this.vehicleId.isEmpty()) {
            setValuesFromPositionPacket(packet.change(), packet.relatives(), player, false);
        }
    }

    private static boolean setValuesFromPositionPacket(PositionMoveRotation change, Set<Relative> relatives, Entity entity, boolean interpolate) {
        PositionMoveRotation currentValues = PositionMoveRotation.of(entity);
        PositionMoveRotation newValues = PositionMoveRotation.calculateAbsolute(currentValues, change, relatives);
        boolean tooBigToInterpolate = currentValues.position().distanceToSqr(newValues.position()) > 4096.0;
        if (interpolate && !tooBigToInterpolate) {
            entity.moveOrInterpolateTo(newValues.position(), newValues.yRot(), newValues.xRot());
            entity.setDeltaMovement(newValues.deltaMovement());
            return true;
        }
        entity.setPos(newValues.position());
        entity.setDeltaMovement(newValues.deltaMovement());
        entity.setYRot(newValues.yRot());
        entity.setXRot(newValues.xRot());
        PositionMoveRotation currentInterpolationValues = new PositionMoveRotation(entity.oldPosition(), Vec3.ZERO, entity.yRotO, entity.xRotO);
        PositionMoveRotation interpolationValues = PositionMoveRotation.calculateAbsolute(currentInterpolationValues, change, relatives);
        entity.setOldPosAndRot(interpolationValues.position(), interpolationValues.yRot(), interpolationValues.xRot());
        return false;
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
        if (current == null || this.vehicleId.isPresent()) {
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

    private void handleTeleportEntity(ClientboundTeleportEntityPacket packet, SandboxLevel level, SandboxPlayer player) {
        Entity entity = level.getEntity(packet.id());
        if (entity == null) {
            if (this.removedPlayerVehicleId.isPresent() && this.removedPlayerVehicleId.getAsInt() == packet.id()) {
                setValuesFromPositionPacket(packet.change(), packet.relatives(), player, false);
            }
        } else {
            boolean hasRelative = packet.relatives().contains(Relative.X) || packet.relatives().contains(Relative.Y) || packet.relatives().contains(Relative.Z);
            boolean interpolate = level.isTickingEntity(entity) || !entity.isLocalInstanceAuthoritative() || hasRelative;
            setValuesFromPositionPacket(packet.change(), packet.relatives(), entity, interpolate);
            entity.setOnGround(packet.onGround());
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
            this.setLocalMode(GameType.byId(param), player);
        } else if (event == ClientboundGameEventPacket.RAIN_LEVEL_CHANGE) {
            level.setRainLevel(paramFloat);
        } else if (event == ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE) {
            level.setThunderLevel(paramFloat);
        }
    }

    private static void handleUpdateAttributes(ClientboundUpdateAttributesPacket packet, SandboxLevel level) {
        Entity entity = level.getEntity(packet.getEntityId());
        if (entity != null) {
            if (!(entity instanceof LivingEntity livingEntity)) {
                throw new IllegalStateException("Server tried to update attributes of a non-living entity (actually: " + entity + ")");
            }
            AttributeMap attributes = livingEntity.getAttributes();

            for (ClientboundUpdateAttributesPacket.AttributeSnapshot attribute : packet.getValues()) {
                AttributeInstance instance = attributes.getInstance(attribute.attribute());
                if (instance == null) {
                    LOGGER.warn("Entity {} does not have attribute {}", entity, attribute.attribute().getRegisteredName());
                } else {
                    instance.setBaseValue(attribute.base());
                    instance.removeModifiers();

                    for (AttributeModifier modifier : attribute.modifiers()) {
                        instance.addTransientModifier(modifier);
                    }
                }
            }
        }
    }

    private static void handleUpdateMobEffect(ClientboundUpdateMobEffectPacket packet, SandboxLevel level) {
        if (level.getEntity(packet.getEntityId()) instanceof LivingEntity livingEntity) {
            MobEffectInstance mobEffectInstance = new MobEffectInstance(
                    packet.getEffect(),
                    packet.getEffectDurationTicks(),
                    packet.getEffectAmplifier(),
                    packet.isEffectAmbient(),
                    packet.isEffectVisible(),
                    packet.effectShowsIcon(),
                    null
            );
            if (!packet.shouldBlend()) {
                mobEffectInstance.skipBlending();
            }

            livingEntity.forceAddEffect(mobEffectInstance, null);
        }
    }

    private void handleSetEntityData(ClientboundSetEntityDataPacket packet, SandboxLevel level, SandboxPlayer player) {
        Entity entity = level.getEntity(packet.id());
        if (entity != null) {
            entity.getEntityData().assignValues(packet.packedItems());
            if (entity == player) {
                if (player.serverReportsItemUse()) {
                    this.serverConfirmedItemUse = true;
                } else if (this.serverConfirmedItemUse) {
                    this.serverConfirmedItemUse = false;
                    this.itemUseMayBeActive = false;
                }
            }
        }
    }

    private void handleSetPassengers(ClientboundSetPassengersPacket packet, SandboxPlayer player) {
        boolean playerIsPassenger = false;
        for (int passengerId : packet.getPassengers()) {
            if (passengerId == player.getId()) {
                playerIsPassenger = true;
                break;
            }
        }
        if (playerIsPassenger) {
            this.vehicleId = OptionalInt.of(packet.getVehicle());
            this.removedPlayerVehicleId = OptionalInt.empty();
        } else if (this.vehicleId.isPresent() && this.vehicleId.getAsInt() == packet.getVehicle()) {
            // - The client ejects every passenger before mounting the listed ones -
            this.stopRiding();
        }
    }

    private void handleRemoveEntities(ClientboundRemoveEntitiesPacket packet, SandboxLevel level, SandboxPlayer player) {
        packet.entityIds().forEach(entityId -> {
            if (this.vehicleId.isPresent() && this.vehicleId.getAsInt() == entityId) {
                this.removedPlayerVehicleId = OptionalInt.of(entityId);
                this.stopRiding();
            }
            Entity entity = level.getEntity(entityId);
            if (entity != null) {
                level.removeEntity(entityId, Entity.RemovalReason.DISCARDED);
                if (entity == player) {
                    this.tickPackets.notes.add("the server removed the player entity");
                }
            }
        });
    }

    private void stopRiding() {
        this.vehicleId = OptionalInt.empty();
        this.positionNeedsResync = true;
    }

    private void handleSetCamera(ClientboundSetCameraPacket packet, SandboxPlayer player) {
        // - The client switches to the entity when it knows it. Other entities are not tracked here; the server -
        // - only makes the camera follow entities the client tracks, so every other id is taken as known -
        this.cameraOnPlayer = cameraEntityId(packet) == player.getId();
    }

    private static int cameraEntityId(ClientboundSetCameraPacket packet) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ClientboundSetCameraPacket.STREAM_CODEC.encode(buffer, packet);
            return buffer.readVarInt();
        } finally {
            buffer.release();
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
            this.playerInfoMap.putIfAbsent(entry.profileId(), new PlayerInfoState());
        }

        if (packet.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE)) {
            for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries()) {
                PlayerInfoState info = this.playerInfoMap.get(entry.profileId());
                if (info != null) {
                    if (info.gameMode != entry.gameMode() && this.player != null && this.player.getUUID().equals(entry.profileId())) {
                        this.player.onGameModeChanged(entry.gameMode());
                    }
                    info.gameMode = entry.gameMode();
                }
            }
        }
    }

    // - ServerboundPlayerLoadedPacket: the client reports it once its player starts ticking -
    void onPlayerLoaded() {
        this.clientLoaded = true;
    }

    // - Block interactions whose results the client predicts before the server confirms them -
    void onBlockPrediction(int sequence) {
        if (sequence > 0) {
            this.pendingPredictionSequence = Math.max(this.pendingPredictionSequence, sequence);
        }
    }

    void onItemUseRequested() {
        this.itemUseMayBeActive = true;
        this.serverConfirmedItemUse = false;
    }

    void onItemReleased() {
        this.itemUseMayBeActive = false;
    }

    void onFallFlyingStartReported() {
        this.tickPackets.fallFlyingStartReported = true;
        this.fallFlyingMayBeActive = true;
    }

    // - Minecraft.tick for one client tick, then the comparison of LocalPlayer.sendChanges with what the client sent -
    ClientTickReport tick(long clientTick) {
        Input reportedKeys = this.lastSent.input;
        boolean reportedSprinting = this.lastSent.sprinting;
        SandboxLevel tickLevel = this.level;
        SandboxPlayer tickPlayer = this.player;
        if (tickLevel == null || tickPlayer == null) {
            throw new IllegalStateException("The client ended a tick before it joined a level");
        }
        tickLevel.tickRateManager().tick();
        List<String> uncertainties = new ArrayList<>(this.tickPackets.uncertainties);
        List<String> notes = new ArrayList<>(this.tickPackets.notes);
        ClientTickReport report;
        if (!this.clientLoaded || tickPlayer.isRemoved()) {
            // - The client ticks its level but not the player, and sends no movement -
            tickLevel.tickEntities();
            tickLevel.tickBlockEntities();
            notes.add(this.clientLoaded ? "player removed" : "client has not loaded the level");
            report = this.notSimulated(clientTick, tickPlayer, reportedSprinting, notes);
        } else if (this.vehicleId.isPresent()) {
            // - The vehicle is not simulated, so neither is the player riding it; the client sends only its rotation -
            ServerboundMovePlayerPacket movePacket = this.tickPackets.movePacket;
            if (movePacket != null && movePacket.hasRotation()) {
                tickPlayer.setYRot(movePacket.getYRot(tickPlayer.getYRot()));
                tickPlayer.setXRot(movePacket.getXRot(tickPlayer.getXRot()));
            }
            tickLevel.tickBlockEntities();
            notes.add("riding entity " + this.vehicleId.getAsInt());
            report = this.notSimulated(clientTick, tickPlayer, reportedSprinting, notes);
        } else {
            ServerboundMovePlayerPacket movePacket = this.tickPackets.movePacket;
            if (movePacket != null && movePacket.hasRotation()) {
                tickPlayer.setYRot(movePacket.getYRot(tickPlayer.getYRot()));
                tickPlayer.setXRot(movePacket.getXRot(tickPlayer.getXRot()));
            }
            tickPlayer.setReportedKeys(reportedKeys);
            this.collectOngoingUncertainties(uncertainties);
            Vec3 positionBeforeTick = tickPlayer.position();
            tickLevel.tickEntities();
            tickLevel.tickBlockEntities();
            report = this.compareWithClient(clientTick, tickPlayer, positionBeforeTick, reportedSprinting, uncertainties, notes);
        }
        tickLevel.tick();
        this.predictionEndedBeforeTick = false;
        this.tickPackets.reset();
        return report;
    }

    private void collectOngoingUncertainties(List<String> uncertainties) {
        if (this.positionNeedsResync) {
            uncertainties.add("position unknown after riding");
        }
        if (this.pendingPredictionSequence != 0) {
            uncertainties.add("block prediction pending");
        }
        if (this.predictionEndedBeforeTick) {
            uncertainties.add("block prediction ended");
        }
        if (this.itemUseMayBeActive) {
            uncertainties.add("item use");
        }
        if (this.fallFlyingMayBeActive) {
            uncertainties.add("fall flying");
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

        boolean sendsMovement = this.cameraOnPlayer;
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
        if (positionReported && (predictedX != reportedX || predictedY != reportedY || predictedZ != reportedZ)) {
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
        if (this.fallFlyingMayBeActive && reportedOnGround && !packets.fallFlyingStartReported) {
            this.fallFlyingMayBeActive = false;
        }

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
    // - after a collision). That scale is read from what the simulation itself just did, and the velocity is set to -
    // - what the same scale gives for the movement the client made. An axis the simulation did not move along gives -
    // - no scale and keeps its velocity; vertical velocity also depends on gravity, which is not a scale, and is kept -
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
        return predictedVelocity / predictedMovement * reportedMovement;
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

    // - PlayerInfo.gameMode of a tab list entry -
    private static final class PlayerInfoState {
        private GameType gameMode = GameType.DEFAULT_MODE;
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
