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
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.PositionAndRotation;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
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
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
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
    // - Blocks beside the boxes a movement sweeps through still change it: the block the player stands on, the step -
    // - up, the blocks it touches -
    private static final double MOVEMENT_BLOCK_REACH = 1.0;
    // - A broken block takes its second half along (a door, a bed, a tall plant), which lies next to it -
    private static final double SECOND_HALF_REACH = 1.0;
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
    // - A boat turns its rider by the boat's turn of the tick and keeps it within reach of the boat's heading, in -
    // - float arithmetic (see checkRiderUses). The client reports the boat's yaw, so the sandbox knows the boat's turn -
    // - only up to a unit in the last place of that yaw; each of the float operations rounds by at most half a unit -
    // - in the last place of values no larger than the yaws plus half a turn (Mth.wrapDegrees keeps the rider within -
    // - half a turn of the boat). The rider's yaw a vanilla client reports therefore lies within a few units in the -
    // - last place of that size from where the sandbox turns the yaw it used an item with; this many leave a margin -
    private static final int RIDER_ROTATION_ULPS = 8;
    private static final float HALF_TURN_DEGREES = 180.0F;

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
    // - The player when the current tick began, for its report -
    private ClientTickReport.Start tickStart = ClientTickReport.Start.none(false);
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
    // - The items the player used during the tick while a boat turns it, whose yaw only the tick decides on (see -
    // - checkUseItem and checkRiderUses) -
    private final List<RiderUse> riderUses = new ArrayList<>();
    // - A riptide trident use whose start depended on rain the client's sky light may not have shown yet (see -
    // - rainDependsOnLaggingSkyLight): the client's use may have begun where the sandbox's did not, or the other way -
    // - round. Every tick tries the other state (see otherTridentUse) until a tick matches only with it, the client's -
    // - next use or release of an item shows its state, or the hand no longer holds the trident -
    private @Nullable UncertainTridentUse uncertainTridentUse;
    // - The alternative of the uncertain trident use that the current tick offers -
    private @Nullable TickAlternative tridentUseAlternative;
    // - The client tick being simulated -
    private long currentClientTick;
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
            case ClientboundBlockChangedAckPacket ack -> {
                level.handleBlockChangedAck(ack.sequence());
                this.gameMode.onBlockChangedAck(ack.sequence());
            }
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
            case ClientboundMoveVehiclePacket moveVehicle -> this.checkVehicleCorrectionAnswer(EntityHandlers.handleMoveVehicle(moveVehicle, player));
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
            this.uncertainTridentUse = null;
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
        this.uncertainTridentUse = null;
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

    // - The client answered the server's correction of the vehicle it steers right away (see -
    // - EntityHandlers.handleMoveVehicle, which gives the answer a vanilla client sends). The answer is no position of -
    // - the tick's own and leaves the tick's positions: it would otherwise pass for the steering of a tick in which -
    // - the player lets go of the vehicle or leaves it, which sends no position of its own (see inferHotbarSwitch). -
    // - It has to carry the vehicle's position, rotation and ground state right after the correction, which the -
    // - sandbox's vehicle has as well. A client that sent none did not steer the vehicle then; the tick's own packets -
    // - show that, and whether it took the correction -
    private void checkVehicleCorrectionAnswer(@Nullable ServerboundMoveVehiclePacket expected) {
        if (expected == null) {
            return;
        }
        ServerboundMoveVehiclePacket answer = this.tickPackets.takeCorrectionAnswer();
        if (answer == null) {
            this.tickPackets.notes.add("the client did not answer a correction of the vehicle the sandbox steers");
            return;
        }
        PositionAndRotation answered = answer.movingTo();
        PositionAndRotation corrected = expected.movingTo();
        Vec3 answeredPosition = answered.position();
        Vec3 correctedPosition = corrected.position();
        List<String> differences = new ArrayList<>();
        if (answeredPosition.x != correctedPosition.x || answeredPosition.y != correctedPosition.y || answeredPosition.z != correctedPosition.z) {
            differences.add(String.format(Locale.ROOT, "vehicle position %.4g blocks off", correctedPosition.distanceTo(answeredPosition)));
        }
        if (answered.yRot() != corrected.yRot() || answered.xRot() != corrected.xRot()) {
            differences.add("vehicle rotation");
        }
        if (answer.onGround() != expected.onGround()) {
            differences.add("vehicle on ground");
        }
        if (differences.isEmpty()) {
            this.tickPackets.notes.add("the client answered a correction of the vehicle");
        } else {
            this.tickPackets.reject(Check.VEHICLE, "the answer to the correction of the vehicle differs in: " + String.join(", ", differences));
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
    List<ClientTickReport> tick(long clientTick, boolean repositionPending) {
        this.tickStart = this.currentStart(repositionPending);
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
    List<ClientTickReport> skipTick(long clientTick, Flag rejection, boolean repositionPending) {
        this.tickStart = this.currentStart(repositionPending);
        List<ClientTickReport> reports = new ArrayList<>(2);
        ClientTickReport held = this.takeHeldReport("the next tick, which had to report the hotbar switch this tick assumed, came too soon to be simulated");
        if (held != null) {
            reports.add(held);
        }
        reports.add(this.unsimulatedTick(clientTick, rejection));
        this.tickPackets.reset();
        return reports;
    }

    // - The player as the tick begins, with the server's packets the client had processed before it applied -
    private ClientTickReport.Start currentStart(boolean repositionPending) {
        SandboxPlayer current = this.player;
        if (current == null) {
            return ClientTickReport.Start.none(repositionPending);
        }
        Vec3 velocity = current.getDeltaMovement();
        return new ClientTickReport.Start(current.getX(), current.getY(), current.getZ(), velocity.x, velocity.y, velocity.z, repositionPending);
    }

    // - The server's packets that put the player somewhere: after one of them the player is where the server wants -
    // - it, whatever it did before -
    static boolean repositionsPlayer(Packet<?> packet) {
        return packet instanceof ClientboundPlayerPositionPacket
                || packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket
                || packet instanceof ClientboundStartConfigurationPacket;
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
        this.currentClientTick = clientTick;
        this.tridentUseAlternative = null;
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
        this.checkRiderUses(tickPlayer);
        List<String> uncertainties = new ArrayList<>(this.tickPackets.uncertainties);
        this.collectOngoingUncertainties(uncertainties, tickPlayer, positionBeforeTick, vehicleBeforeTick, vehiclePositionBeforeTick);
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
                // - The tick matched only with the other state of the uncertain trident use, which the sandbox now has -
                if (this.tridentUseAlternative != null && matched.combination().contains(this.tridentUseAlternative)) {
                    this.uncertainTridentUse = null;
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
        UncertainTridentUse uncertainUse = this.uncertainTridentUse;
        if (uncertainUse != null) {
            if (ItemStack.isSameItem(player.getItemInHand(uncertainUse.hand()), uncertainUse.trident())) {
                this.tridentUseAlternative = this.otherTridentUse(uncertainUse, player);
                alternatives.add(this.tridentUseAlternative);
            } else {
                // - The hand holds another item now, which ends the use on both sides (LivingEntity.updatingUsingItem) -
                this.uncertainTridentUse = null;
            }
        }
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
        AbstractBoat boat = turningBoat(player);
        if (vehicleMove != null && boat != null && boat.isLocalInstanceAuthoritative()) {
            return vehicleMove.movingTo().yRot() - boat.getYRot();
        }
        return 0.0F;
    }

    // - The boat whose AbstractBoat.positionRider turns the player at the end of the player's tick and clamps its -
    // - yaw (clampRotation), unless the player's type can turn in boats -
    private static @Nullable AbstractBoat turningBoat(SandboxPlayer player) {
        return player.getVehicle() instanceof AbstractBoat boat && !player.is(EntityTypeTags.CAN_TURN_IN_BOATS) ? boat : null;
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
                Double.NaN, null, this.tickStart, rejections, notes
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

    // - The player as Minecraft.handleKeybinds finds it, before any action of the tick, and what the crosshair pointed -
    // - at. While the player uses an item, the attack and use keys do nothing; a hotbar switch that starts the tick -
    // - may have stopped a use of the main hand during the previous tick already (see leadingHotbarSwitch), so only a -
    // - use that such a switch leaves alone counts. Paddling a boat keeps the player's hands busy. The crosshair is -
    // - what Minecraft.pick found from the camera before the key handling, so later actions of the tick do not change -
    // - it. It depends on the item the player holds out (SandboxPlayer.raycastHitResult): after such a switch, the -
    // - client's player held the new item already, unless it switched during this tick's key handling, so where the -
    // - two items pick differently either crosshair is possible. The interaction ranges the pick uses are the -
    // - attributes the server sent, as the client applies an item's attribute modifiers only when the server sends -
    // - them (LivingEntity.detectEquipmentUpdates runs on the server), so a switch does not change them. pickReach is -
    // - how far along the camera's sight line the pick looked with either item (see pickReach) -
    private record KeyHandlingStart(
            boolean usingItem, boolean useMayHaveStopped, boolean handsBusy, Entity camera, HitResult crosshair, @Nullable HitResult crosshairWithSwitchedItem,
            double pickReach
    ) {

        // - Every crosshair the client may have had -
        List<HitResult> crosshairs() {
            return this.crosshairWithSwitchedItem == null ? List.of(this.crosshair) : List.of(this.crosshair, this.crosshairWithSwitchedItem);
        }

        // - Where the crosshair met the entity with each item the client may have held out; empty when it did not -
        List<EntityHitResult> hitsOn(Entity target) {
            List<EntityHitResult> hits = new ArrayList<>(2);
            for (HitResult crosshair : this.crosshairs()) {
                if (crosshair instanceof EntityHitResult hit && hit.getEntity() == target) {
                    hits.add(hit);
                }
            }
            return hits;
        }

        // - The block hits of the crosshairs the client may have had -
        List<BlockHitResult> blockHits() {
            List<BlockHitResult> hits = new ArrayList<>(2);
            for (HitResult crosshair : this.crosshairs()) {
                if (crosshair instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
                    hits.add(hit);
                }
            }
            return hits;
        }

        // - Whether the crosshair pointed at this face of this block with an item the client may have held out -
        boolean pointsAt(BlockPos pos, Direction face) {
            return this.blockHits().stream().anyMatch(hit -> hit.getBlockPos().equals(pos) && hit.getDirection() == face);
        }
    }

    // - An action of the tick under check: what the client did, in words, the packet it sent for it and the block -
    // - prediction that packet carries (see Flag) -
    private record CheckedAction(String description, long packet, int predictionSequence) {
    }

    // - An item use while a boat turns the player: the use, the rotation the client sent with it and the boat -
    private record RiderUse(CheckedAction action, float yRot, float xRot, AbstractBoat boat) {
    }

    // - A trident use the sandbox is unsure of (see uncertainTridentUse): the hand, the trident, and the client tick in -
    // - whose key handling the use began or failed to -
    private record UncertainTridentUse(InteractionHand hand, ItemStack trident, long startTick) {
    }

    // - Replays Minecraft.handleKeybinds and MultiPlayerGameMode.tick for this tick from the packets they sent. An -
    // - action the sandbox's vanilla code cannot perform is rejected, and the others go on. The hotbar keys come -
    // - first in the key handling and nothing else in a tick changes the selected slot, so every action of the key -
    // - handling was made with the slot they selected. The first action that calls -
    // - MultiPlayerGameMode.ensureHasSentCarriedItem reports that slot right before itself (see reportsCarriedItem), -
    // - while Minecraft.startAttack's start of breaking and swing, the aborts and the offhand swap do not and may come -
    // - first, so the sandbox selects a slot reported during the key handling before its first action (see -
    // - keyHandlingReport). Without such a report, the slot is known to be the one selected already once the tick -
    // - shows an action that would have reported another -
    private void performTickActions(SandboxLevel level, SandboxPlayer player) {
        this.riderUses.clear();
        List<Packet<?>> actions = this.tickPackets.actions;
        if (actions.isEmpty()) {
            return;
        }
        ServerboundSetCarriedItemPacket leadingSwitch = this.leadingHotbarSwitch(player);
        ItemStack switchedTo = leadingSwitch != null ? player.getInventory().getItem(leadingSwitch.getSlot()) : null;
        // - LivingEntity.updatingUsingItem stops the use once the used hand holds another item -
        boolean useMayHaveStopped = switchedTo != null && player.isUsingItem() && player.getUsedItemHand() == InteractionHand.MAIN_HAND
                && !ItemStack.isSameItem(switchedTo, player.getUseItem());
        Entity camera = this.cameraEntity != null ? this.cameraEntity : player;
        ItemStack activeItem = player.getActiveItem();
        // - LivingEntity.getActiveItem holds out the new main hand item unless the player is a spectator or goes on -
        // - using an item -
        boolean switchedItemHeldOut = switchedTo != null && !player.isSpectator() && (!player.isUsingItem() || useMayHaveStopped);
        HitResult crosshairWithSwitchedItem = switchedItemHeldOut
                && !Objects.equals(switchedTo.get(DataComponents.ATTACK_RANGE), activeItem.get(DataComponents.ATTACK_RANGE))
                ? player.raycastHitResult(TICK_PARTIAL_TICK, camera, switchedTo)
                : null;
        double pickReach = switchedItemHeldOut ? Math.max(pickReach(player, activeItem), pickReach(player, switchedTo)) : pickReach(player, activeItem);
        // - While the sandbox is unsure whether the client's trident use began, the client may use no item where the -
        // - sandbox's player uses the trident -
        boolean useUncertain = useMayHaveStopped || this.uncertainTridentUse != null && player.isUsingItem();
        KeyHandlingStart start = new KeyHandlingStart(player.isUsingItem() && !useUncertain, useUncertain, player.isHandsBusy(), camera,
                player.raycastHitResult(TICK_PARTIAL_TICK, camera), crosshairWithSwitchedItem, pickReach);
        int keyHandlingReport = keyHandlingReport(actions);
        int firstKeyHandlingAction = actions.getFirst() instanceof ServerboundSetCarriedItemPacket ? 1 : 0;
        boolean keyHandlingSlotKnown = keyHandlingReport >= 0 || actions.stream().anyMatch(PlayConnection::reportsCarriedItem);
        boolean swingAccompanied = false;
        for (int index = 0; index < actions.size(); index++) {
            if (index == firstKeyHandlingAction && keyHandlingReport > index) {
                this.selectHotbarSlot(player, ((ServerboundSetCarriedItemPacket) actions.get(keyHandlingReport)).getSlot(), false);
            }
            Packet<?> action = actions.get(index);
            try {
                swingAccompanied = this.performTickAction(action, index, leadingSwitch, start, keyHandlingSlotKnown, swingAccompanied, level, player);
            } catch (RuntimeException problem) {
                this.problemLog.log("rejected " + action.type() + " replayed at the end of a client tick", problem);
                this.tickPackets.reject(Check.BAD_PACKETS, action.type() + " could not be performed (" + problem + ")");
            }
        }
    }

    // - Performs one action of the tick; returns whether a swing that follows is part of an attack or a block action. -
    // - Every action is checked against what the client's key handling allows before it is performed; the sandbox -
    // - still performs a rejected one as the client did, since the client's player went on from it. keyHandlingSlotKnown -
    // - tells whether the tick shows the slot its key handling acted with (see performTickActions) -
    private boolean performTickAction(
            Packet<?> action,
            int index,
            @Nullable ServerboundSetCarriedItemPacket leadingSwitch,
            KeyHandlingStart start,
            boolean keyHandlingSlotKnown,
            boolean swingAccompanied,
            SandboxLevel level,
            SandboxPlayer player
    ) {
        List<Packet<?>> actions = this.tickPackets.actions;
        long packet = this.tickPackets.actionPackets.get(index);
        switch (action) {
            case ServerboundSetCarriedItemPacket carriedItem -> {
                this.checkHotbarReport(carriedItem, index, packet);
                this.selectHotbarSlot(player, carriedItem.getSlot(), carriedItem == leadingSwitch);
            }
            case ServerboundPlayerActionPacket playerAction -> {
                this.checkPlayerAction(level, player, playerAction, packet, keyHandlingSlotKnown, start);
                Packet<?> next = index + 1 < actions.size() ? actions.get(index + 1) : null;
                return this.performPlayerAction(playerAction, next, keyHandlingSlotKnown, this.onlySwingsFollow(index), level, player) || swingAccompanied;
            }
            case ServerboundUseItemOnPacket useItemOn -> {
                this.checkUseItemOn(level, player, useItemOn, packet, start);
                // - A placed block faces by the player's rotation -
                this.checkActionRotation(player, "used an item on a block");
                this.gameMode.useItemOn(level, player, useItemOn.hand(), useItemOn.hitResult(), useItemOn.sequence(), this.tickPackets);
            }
            case ServerboundUseItemPacket useItem -> {
                // - The rotation the packet carries is the player's own, which the tick's movement reported (see -
                // - checkUseItem); the player keeps that one -
                this.checkUseItem(level, player, useItem, packet, start);
                this.useItem(level, player, useItem);
            }
            case ServerboundAttackPacket attack -> {
                Entity target = level.getEntity(attack.entityId());
                if (target == null) {
                    this.attackUnknownEntity(player, this.onlySwingsFollow(index));
                } else {
                    this.checkAttack(level, player, target, new CheckedAction("attacked " + describeEntity(target), packet, Flag.NO_PREDICTION), start);
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
                    this.checkInteraction(level, player, target, interact.hand(),
                            new CheckedAction("interacted with " + describeEntity(target), packet, Flag.NO_PREDICTION), start);
                    this.gameMode.interact(player, target, interact.hand(), interact.location());
                }
            }
            case ServerboundPunchPacket ignored -> {
                if (!swingAccompanied) {
                    // - What the crosshair pointed at depends on the player's rotation -
                    this.checkActionRotation(player, "swung at what the crosshair pointed at");
                    this.gameMode.swingAlone(level, player, start.crosshair(), index == actions.size() - 1, this.uncertainBreakOnSight(start) == null);
                }
                return false;
            }
            default -> throw new IllegalArgumentException("No handler for the action " + action.type());
        }
        return swingAccompanied;
    }

    // - What the client's key handling could do depends on the items it held: the item in the hand, the attack range -
    // - it picks along, whether it is in use. While the sandbox's items differ from the client's (see -
    // - markInventoryUnknown), what a check finds is noted instead -
    private void reject(CheckedAction action, Check check, String detail) {
        if (this.unknownInventoryMenu.isPresent()) {
            this.tickPackets.notes.add("not checked: " + detail + ", while the sandbox's items differ from the client's");
            return;
        }
        this.tickPackets.rejectAction(check, detail, action.packet(), action.predictionSequence());
    }

    // - A rejection that a block the client may have broken otherwise than the sandbox can explain (see -
    // - SandboxGameMode.startDestroyBlock), which is then noted instead -
    private void rejectUnlessUncertain(CheckedAction action, Check check, String detail, @Nullable String uncertainty) {
        if (uncertainty != null) {
            this.tickPackets.notes.add("not checked: " + detail + ", since " + uncertainty);
            return;
        }
        this.reject(action, check, detail);
    }

    // - The block prediction a packet carries: MultiPlayerGameMode.startPrediction numbers them from 1, and a packet -
    // - sent without one carries 0 -
    private static int predictionOf(int sequence) {
        return sequence > 0 ? sequence : Flag.NO_PREDICTION;
    }

    // - Minecraft.startAttack, the only place a vanilla client attacks from (MultiPlayerGameMode.attack). The attack -
    // - key does nothing while the player uses an item or paddles a boat (see checkHandsFree), and a spectator -
    // - spectates the entity instead. An item the level's features do not enable does not attack, a piercing weapon -
    // - stabs instead, and a weapon charged less than its minimum attack charge does nothing. Otherwise the player -
    // - attacks the entity the crosshair points at, with a weapon that has an attack range only within that range -
    private void checkAttack(SandboxLevel level, SandboxPlayer player, Entity target, CheckedAction action, KeyHandlingStart start) {
        this.checkHandsFree(action, "attack", start);
        if (this.gameMode.isSpectator()) {
            this.reject(action, Check.INTERACTION, action.description() + " as a spectator, who spectates an entity instead");
        }
        ItemStack weapon = player.getMainHandItem();
        if (!weapon.isItemEnabled(level.enabledFeatures())) {
            this.reject(action, Check.INTERACTION, action.description() + " with " + describeItem(weapon) + ", which the level's features do not enable");
        } else if (weapon.has(DataComponents.PIERCING_WEAPON)) {
            // - Charged too little, a piercing weapon does nothing at all -
            this.reject(action, Check.INTERACTION, action.description() + " with " + describeItem(weapon) + ", which a vanilla client stabs with instead");
        } else {
            this.checkCharge(player, weapon, action);
        }
        AttackRange weaponRange = weapon.get(DataComponents.ATTACK_RANGE);
        List<EntityHitResult> hits = start.hitsOn(target);
        if (!hits.isEmpty()) {
            // - AttackRange.isInRange measures from the player's eyes to the point the crosshair met -
            if (weaponRange != null && hits.stream().noneMatch(hit -> weaponRange.isInRange(player, hit.getLocation()))) {
                this.reject(action, Check.REACH, String.format(Locale.ROOT, "%s %.2f blocks away, outside the %.2f to %.2f blocks %s reaches",
                        action.description(), hits.getFirst().getLocation().distanceTo(player.getEyePosition()),
                        weaponRange.effectiveMinRange(player) - weaponRange.hitboxMargin(),
                        weaponRange.effectiveMaxRange(player) + weaponRange.hitboxMargin(), describeItem(weapon)));
            }
            return;
        }
        // - Minecraft.pick meets the entity's box grown by its pick radius closer than the entity interaction range to -
        // - the camera's eyes, and a weapon with an attack range then needs the point it met within that range -
        double distance = Math.sqrt(target.getBoundingBox().inflate(target.getPickRadius()).distanceToSqr(start.camera().getEyePosition(TICK_PARTIAL_TICK)));
        if (weaponRange != null) {
            double maximumRange = weaponRange.effectiveMaxRange(player) + weaponRange.hitboxMargin();
            if (distance > maximumRange) {
                this.rejectOutOfReach(action, distance, maximumRange, describeItem(weapon));
            } else {
                this.rejectCrosshairMiss(action, player, target, start, maximumRange);
            }
        } else if (distance >= player.entityInteractionRange()) {
            this.rejectOutOfReach(action, distance, player.entityInteractionRange(), "the player");
        } else {
            this.rejectCrosshairMiss(action, player, target, start, player.entityInteractionRange());
        }
    }

    // - Player.cannotAttackWithItem as Minecraft.startAttack asks it before it attacks or stabs. The attack strength -
    // - ticker depends on when the client switched its hotbar slot (see SandboxPlayer.considerEarlierHotbarSwitch); the -
    // - attack speed is the attribute the server sent -
    private void checkCharge(SandboxPlayer player, ItemStack weapon, CheckedAction action) {
        if (!player.cannotAttackWithItem(weapon, 0)) {
            return;
        }
        if (player.cannotAttackWithItemUnderAnyTicker(weapon)) {
            this.reject(action, Check.INTERACTION, action.description() + " with " + describeItem(weapon) + " charged less than its minimum attack charge");
        } else {
            this.tickPackets.notes.add("not checked: " + action.description() + " with " + describeItem(weapon)
                    + ", whose charge depends on when the client switched its hotbar slot");
        }
    }

    // - Minecraft.startUseItem, the only place a vanilla client interacts with an entity from -
    // - (MultiPlayerGameMode.interact, which sends the interaction for a spectator as well). The use key does nothing -
    // - while the player uses an item or paddles a boat (see checkHandsFree) or breaks a block, and the hands are -
    // - tried as checkItemsEnabled says. The player interacts with the entity the crosshair points at, within the -
    // - world border, while the entity's box lies closer than the player's entity interaction range -
    // - (Player.isWithinEntityInteractionRange) -
    private void checkInteraction(SandboxLevel level, SandboxPlayer player, Entity target, InteractionHand hand, CheckedAction action, KeyHandlingStart start) {
        this.checkHandsFree(action, "use", start);
        this.checkNotBreaking(action);
        this.checkItemsEnabled(level, player, hand, action);
        if (!level.getWorldBorder().isWithinBounds(target.blockPosition())) {
            this.reject(action, Check.INTERACTION, action.description() + " outside the world border");
        }
        double reach = player.entityInteractionRange();
        if (!player.isWithinEntityInteractionRange(target, 0.0)) {
            this.rejectOutOfReach(action, Math.sqrt(target.getBoundingBox().distanceToSqr(player.getEyePosition())), reach, "the player");
        } else if (start.hitsOn(target).isEmpty()) {
            this.rejectCrosshairMiss(action, player, target, start, reach);
        }
    }

    // - The block actions of MultiPlayerGameMode and the stab of a piercing weapon, as the client's key handling -
    // - allows them (see checkBlockBreaking and checkStab). The client drops, swaps and releases items whenever it -
    // - handles its keys, and aborts breaking whenever it stops, also while the server teleports it -
    // - (ClientPacketListener.handleMovePlayer): those only change what the client itself holds or breaks. Whether the -
    // - client could finish or turn depends on its mining state, which the sandbox knows only while no start of -
    // - breaking left it open (SandboxGameMode.miningStateKnown); a finish or turn comes from continueDestroyBlock, -
    // - which reports the slot it acts with first, while a start's item is known as keyHandlingSlotKnown tells -
    private void checkPlayerAction(
            SandboxLevel level, SandboxPlayer player, ServerboundPlayerActionPacket action, long packet, boolean keyHandlingSlotKnown, KeyHandlingStart start
    ) {
        BlockPos pos = action.getPos();
        int prediction = predictionOf(action.getSequence());
        String miningStateUnknown = this.gameMode.miningStateKnown()
                ? null
                : "the client may have started breaking with another hotbar item than the sandbox, which breaks another block";
        switch (action.getAction()) {
            case START_DESTROY_BLOCK -> this.checkBlockBreaking(level, player, new CheckedAction("started breaking " + describeBlock(level, pos), packet, prediction),
                    pos, action.getDirection(), true, keyHandlingSlotKnown, start);
            case STOP_DESTROY_BLOCK -> {
                CheckedAction finish = new CheckedAction("finished breaking " + describeBlock(level, pos), packet, prediction);
                this.checkBlockBreaking(level, player, finish, pos, action.getDirection(), false, true, start);
                String whyNoFinish = this.gameMode.whyNoFinish(level, player, pos);
                if (whyNoFinish != null) {
                    this.rejectUnlessUncertain(finish, Check.FAST_BREAK, finish.description() + " " + whyNoFinish, miningStateUnknown);
                }
            }
            case CHANGE_DESTROY_DIRECTION -> {
                CheckedAction turn = new CheckedAction("turned to another face of " + describeBlock(level, pos) + " while breaking it", packet, prediction);
                this.checkBlockBreaking(level, player, turn, pos, action.getDirection(), false, true, start);
                // - MultiPlayerGameMode.continueDestroyBlock only turns while it goes on breaking that block -
                if (!this.gameMode.sameDestroyTarget(player, pos)) {
                    this.rejectUnlessUncertain(turn, Check.INTERACTION, turn.description() + ", which a vanilla client only does for the block it is breaking",
                            miningStateUnknown);
                }
            }
            case STAB -> this.checkStab(level, player, new CheckedAction("stabbed", packet, Flag.NO_PREDICTION), start);
            case ABORT_DESTROY_BLOCK, DROP_ITEM, DROP_ALL_ITEMS, RELEASE_USE_ITEM, SWAP_ITEM_WITH_OFFHAND -> {
            }
        }
    }

    // - MultiPlayerGameMode.startDestroyBlock and continueDestroyBlock, from Minecraft.startAttack and continueAttack, -
    // - the only senders of START_DESTROY_BLOCK, STOP_DESTROY_BLOCK and CHANGE_DESTROY_DIRECTION. They act on the block -
    // - and face the crosshair points at, which is not air (both skip air). startAttack does nothing while the player -
    // - uses an item as the key handling starts, and continueAttack, which comes after everything else in it, while -
    // - the player uses one then (a release earlier in the tick ends the use), so a start needs either free and a -
    // - finish or turn the latter. Neither breaks blocks with a piercing weapon: startAttack stabs with it, and -
    // - continueAttack leaves blocks alone. A start also leaves alone a block the game mode keeps the player from -
    // - breaking (Player.blockActionRestricted) and one outside the world border. The item the client started with is -
    // - only known when itemKnown (see SandboxGameMode.startDestroyBlock), so otherwise a check that depends on it -
    // - fails only when no hotbar item passes it -
    private void checkBlockBreaking(
            SandboxLevel level, SandboxPlayer player, CheckedAction action, BlockPos pos, Direction face, boolean starting, boolean itemKnown,
            KeyHandlingStart start
    ) {
        boolean usingItem = player.isUsingItem() && !start.useMayHaveStopped();
        if (starting ? start.usingItem() && usingItem : usingItem) {
            this.reject(action, Check.INTERACTION, action.description() + " while using an item, when a vanilla client does not break blocks");
        }
        if (!SandboxGameMode.anyPossibleItem(player, itemKnown, () -> !player.getMainHandItem().has(DataComponents.PIERCING_WEAPON))) {
            this.reject(action, Check.INTERACTION, action.description() + " with " + describeItem(player.getMainHandItem()) + ", which a vanilla client stabs with instead");
        }
        if (starting && !SandboxGameMode.anyPossibleItem(player, itemKnown, () -> !player.blockActionRestricted(level, pos, this.gameMode.localPlayerMode()))) {
            this.reject(action, Check.INTERACTION, action.description() + ", which its game mode does not let it break");
        }
        if (starting && !level.getWorldBorder().isWithinBounds(pos)) {
            this.reject(action, Check.INTERACTION, action.description() + " outside the world border");
        }
        if (level.getBlockState(pos).isAir()) {
            this.rejectUnlessUncertain(action, Check.HITBOX, action.description() + ", where no block is", this.uncertainBreakOnSight(start));
        } else if (!start.pointsAt(pos, face)) {
            this.rejectBlockMiss(action, player, pos, start, "its " + face.getSerializedName() + " face");
        }
    }

    // - MultiPlayerGameMode.useItemOn, from Minecraft.startUseItem: it sends the crosshair's block hit itself, as -
    // - BlockHitResult.STREAM_CODEC encodes it (see sentAs), only inside the world border, and only while the use key -
    // - acts (see checkHandsFree), the player breaks no block and the hands up to this one hold enabled items -
    private void checkUseItemOn(SandboxLevel level, SandboxPlayer player, ServerboundUseItemOnPacket useItemOn, long packet, KeyHandlingStart start) {
        BlockHitResult sent = useItemOn.hitResult();
        BlockPos pos = sent.getBlockPos();
        CheckedAction action = new CheckedAction("used " + describeItem(player.getItemInHand(useItemOn.hand())) + " on " + describeBlock(level, pos),
                packet, predictionOf(useItemOn.sequence()));
        this.checkHandsFree(action, "use", start);
        this.checkNotBreaking(action);
        this.checkItemsEnabled(level, player, useItemOn.hand(), action);
        if (!level.getWorldBorder().isWithinBounds(pos)) {
            this.reject(action, Check.INTERACTION, action.description() + " outside the world border");
        }
        List<BlockHitResult> crosshairHits = start.blockHits();
        if (crosshairHits.stream().anyMatch(hit -> sentAs(hit, sent))) {
            return;
        }
        String sentFace = "its " + sent.getDirection().getSerializedName() + " face";
        if (start.pointsAt(pos, sent.getDirection())) {
            Vec3 point = sent.getLocation();
            this.rejectUnlessUncertain(action, Check.HITBOX, String.format(Locale.ROOT, "%s at %.4f, %.4f, %.4f of %s, which the crosshair met at %s",
                    action.description(), point.x - pos.getX(), point.y - pos.getY(), point.z - pos.getZ(), sentFace, describeBlockPoints(crosshairHits, pos)),
                    this.uncertainBreakOnSight(start));
        } else {
            this.rejectBlockMiss(action, player, pos, start, sentFace);
        }
    }

    // - Whether the client sends the crosshair's block hit as this one: BlockHitResult.STREAM_CODEC sends the point as -
    // - floats relative to the block, and decoding adds them to the block's corner -
    private static boolean sentAs(BlockHitResult crosshair, BlockHitResult sent) {
        BlockPos pos = crosshair.getBlockPos();
        Vec3 point = crosshair.getLocation();
        Vec3 sentPoint = sent.getLocation();
        return pos.equals(sent.getBlockPos()) && crosshair.getDirection() == sent.getDirection()
                && crosshair.isInside() == sent.isInside() && crosshair.isWorldBorderHit() == sent.isWorldBorderHit()
                && pos.getX() + (double) (float) (point.x - pos.getX()) == sentPoint.x
                && pos.getY() + (double) (float) (point.y - pos.getY()) == sentPoint.y
                && pos.getZ() + (double) (float) (point.z - pos.getZ()) == sentPoint.z;
    }

    // - Where the crosshairs met the block, relative to it -
    private static String describeBlockPoints(List<BlockHitResult> hits, BlockPos pos) {
        List<String> points = new ArrayList<>(hits.size());
        for (BlockHitResult hit : hits) {
            if (hit.getBlockPos().equals(pos)) {
                Vec3 point = hit.getLocation();
                points.add(String.format(Locale.ROOT, "%.4f, %.4f, %.4f", point.x - pos.getX(), point.y - pos.getY(), point.z - pos.getZ()));
            }
        }
        return String.join(" or ", points);
    }

    // - MultiPlayerGameMode.useItem, from Minecraft.startUseItem: with the item in the hand, which is not empty, not as -
    // - a spectator (useItem sends nothing then), only while the use key acts (see checkHandsFree), the player breaks -
    // - no block and the hands up to this one hold enabled items, and facing where the player faces: the packet -
    // - carries the player's rotation of that moment. Nothing turns the player between the key handling and the end -
    // - of its tick but a boat it rides (turningBoat) and a minecart that may turn it (see passengerTurnUnknown), so -
    // - the rotation the tick's movement reported (see simulateTick) is that rotation exactly. A boat turns only the -
    // - yaw, which is decided after the tick (see checkRiderUses) -
    private void checkUseItem(SandboxLevel level, SandboxPlayer player, ServerboundUseItemPacket useItem, long packet, KeyHandlingStart start) {
        ItemStack item = player.getItemInHand(useItem.hand());
        CheckedAction action = new CheckedAction("used " + describeItem(item), packet, predictionOf(useItem.sequence()));
        this.checkHandsFree(action, "use", start);
        this.checkNotBreaking(action);
        if (this.gameMode.isSpectator()) {
            this.reject(action, Check.INTERACTION, action.description() + " as a spectator, who uses no items");
        }
        if (item.isEmpty()) {
            this.reject(action, Check.INTERACTION, action.description() + ", an empty hand, which a vanilla client does not use");
        }
        this.checkItemsEnabled(level, player, useItem.hand(), action);
        if (passengerTurnUnknown(player)) {
            this.tickPackets.notes.add("not checked: " + action.description() + " with a rotation the minecart may have turned");
            return;
        }
        AbstractBoat boat = turningBoat(player);
        // - Entity.setYRot keeps a vanilla client's yaw finite, so a yaw that is not can be rejected right away -
        boolean yawAfterTick = boat != null && Float.isFinite(useItem.yRot());
        if (useItem.xRot() != player.getXRot() || !yawAfterTick && useItem.yRot() != player.getYRot()) {
            this.reject(action, Check.HITBOX, String.format(Locale.ROOT, "%s facing %.3f/%.3f, where the player faced %.3f/%.3f (yaw/pitch)",
                    action.description(), useItem.yRot(), useItem.xRot(), player.getYRot(), player.getXRot()));
        } else if (yawAfterTick) {
            this.riderUses.add(new RiderUse(action, useItem.yRot(), useItem.xRot(), boat));
        }
    }

    // - The yaw a boat's rider used items with (see checkUseItem), once the tick has turned the boat: -
    // - AbstractBoat.positionRider turns the rider by the boat's turn of the tick and clampRotation keeps its yaw -
    // - within 105 degrees of the boat's, after which the client reports the rider's rotation (LocalPlayer.sendChanges). -
    // - The sandbox's boat, as the tick left it, turns the yaw the item was used with the same way, and has to arrive -
    // - where the client's rider did, up to the rounding RIDER_ROTATION_ULPS allows. A boat the client steers has to -
    // - have turned as the client reported, as it decides the rider's turn; one it does not steer turns neither itself -
    // - nor its rider during the tick (AbstractBoat.tick) and follows the server like the client's -
    private void checkRiderUses(SandboxPlayer player) {
        if (this.riderUses.isEmpty()) {
            return;
        }
        ServerboundMovePlayerPacket movePacket = this.tickPackets.movePacket;
        ServerboundMoveVehiclePacket vehicleMove = this.tickPackets.vehicleMove;
        for (RiderUse use : this.riderUses) {
            AbstractBoat boat = use.boat();
            String unchecked = null;
            if (player.getVehicle() != boat) {
                unchecked = "the player no longer rode that boat after the tick";
            } else if (movePacket == null || !movePacket.hasRotation()) {
                unchecked = "the client reported no rotation after the tick";
            } else if (boat.isLocalInstanceAuthoritative()
                    && (boat != player.getRootVehicle() || vehicleMove == null || vehicleMove.movingTo().yRot() != boat.getClientPositionAndRotation().yRot())) {
                unchecked = "the sandbox did not turn the boat as the client reported";
            }
            String described = String.format(Locale.ROOT, "%s facing %.3f/%.3f (yaw/pitch) in a boat", use.action().description(), use.yRot(), use.xRot());
            if (unchecked != null) {
                this.tickPackets.notes.add("not checked: " + described + ", since " + unchecked);
                continue;
            }
            float reportedYaw = movePacket.getYRot(player.getYRot());
            float turnedYaw = riderYawAfterBoatTurn(player, boat, use.yRot());
            float tolerance = RIDER_ROTATION_ULPS * Math.ulp(Math.max(Math.abs(reportedYaw), Math.abs(boat.getYRot())) + HALF_TURN_DEGREES);
            if (Math.abs(turnedYaw - reportedYaw) > tolerance) {
                this.reject(use.action(), Check.HITBOX, String.format(Locale.ROOT, "%s, which the boat turns to a yaw of %.3f, where the client reported %.3f",
                        described, turnedYaw, reportedYaw));
            }
        }
        this.riderUses.clear();
    }

    // - The yaw AbstractBoat.positionRider turns the player to from this yaw, run on the boat and the player as the -
    // - tick left them. Everything positionRider changes on the player is put back afterwards: its yaw, the head and -
    // - body yaw, and its position, which a piston may have moved after the boat positioned it -
    private static float riderYawAfterBoatTurn(SandboxPlayer player, AbstractBoat boat, float yaw) {
        float yRot = player.getYRot();
        float yHeadRot = player.getYHeadRot();
        float yBodyRot = player.yBodyRot;
        Vec3 position = player.position();
        player.setYRot(yaw);
        boat.positionRider(player);
        float turned = player.getYRot();
        player.setYRot(yRot);
        player.setYHeadRot(yHeadRot);
        player.setYBodyRot(yBodyRot);
        player.setPos(position);
        return turned;
    }

    // - MultiPlayerGameMode.piercingAttack, from Minecraft.startAttack: only while the attack key acts (see -
    // - checkHandsFree), not as a spectator (who spectates instead), and with an enabled piercing weapon charged to its -
    // - minimum attack charge -
    private void checkStab(SandboxLevel level, SandboxPlayer player, CheckedAction action, KeyHandlingStart start) {
        this.checkHandsFree(action, "attack", start);
        if (this.gameMode.isSpectator()) {
            this.reject(action, Check.INTERACTION, action.description() + " as a spectator, who spectates instead");
        }
        ItemStack weapon = player.getMainHandItem();
        if (!weapon.isItemEnabled(level.enabledFeatures())) {
            this.reject(action, Check.INTERACTION, action.description() + " with " + describeItem(weapon) + ", which the level's features do not enable");
        } else if (!weapon.has(DataComponents.PIERCING_WEAPON)) {
            this.reject(action, Check.INTERACTION, action.description() + " with " + describeItem(weapon) + ", which is no piercing weapon");
        } else {
            this.checkCharge(player, weapon, action);
        }
    }

    // - Minecraft.handleKeybinds consumes the attack and use key clicks without acting while the player uses an item, -
    // - and Minecraft.startAttack and startUseItem do nothing while paddling a boat keeps the player's hands busy -
    private void checkHandsFree(CheckedAction action, String key, KeyHandlingStart start) {
        if (start.usingItem()) {
            this.reject(action, Check.INTERACTION, action.description() + " while using an item, when a vanilla client ignores the " + key + " key");
        }
        if (start.handsBusy()) {
            this.reject(action, Check.INTERACTION, action.description() + " while paddling a boat, which keeps a vanilla client's hands busy");
        }
    }

    // - Minecraft.startUseItem does nothing while the player breaks a block -
    private void checkNotBreaking(CheckedAction action) {
        if (this.gameMode.isDestroying()) {
            this.reject(action, Check.INTERACTION, action.description() + " while breaking a block, when a vanilla client ignores the use key");
        }
    }

    // - Minecraft.startUseItem tries the hands main hand first, and an item the level's features do not enable ends -
    // - the key's handling -
    private void checkItemsEnabled(SandboxLevel level, SandboxPlayer player, InteractionHand hand, CheckedAction action) {
        for (InteractionHand tried : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(tried);
            if (!held.isItemEnabled(level.enabledFeatures())) {
                this.reject(action, Check.INTERACTION, action.description() + " while holding " + describeItem(held) + ", which the level's features do not enable");
                return;
            }
            if (tried == hand) {
                return;
            }
        }
    }

    private void rejectOutOfReach(CheckedAction action, double distance, double reach, String reacher) {
        this.reject(action, Check.REACH, String.format(Locale.ROOT, "%s %.2f blocks away, beyond the %.2f blocks %s reaches",
                action.description(), distance, reach, reacher));
    }

    // - The crosshair did not point at the target, although the target lay within reach: it is an entity the crosshair -
    // - never points at (EntitySelector.CAN_BE_PICKED), or the crosshair pointed at something in front of it or beside -
    // - it. The sight line reaches as far as the action does. Where the player rides a minecart that may have turned -
    // - it (see passengerTurnUnknown), the rotation the player acted with is unknown, and so is where the crosshair -
    // - pointed; where the client may have broken a block on the sight line otherwise than the sandbox, so is what -
    // - the crosshair met -
    private void rejectCrosshairMiss(CheckedAction action, SandboxPlayer player, Entity target, KeyHandlingStart start, double reach) {
        if (passengerTurnUnknown(player)) {
            this.tickPackets.notes.add("not checked: " + action.description() + " with a rotation the minecart may have turned");
            return;
        }
        HitResult crosshair = start.crosshair();
        if (!EntitySelector.CAN_BE_PICKED.test(target)) {
            this.reject(action, Check.HITBOX, action.description() + ", which the crosshair never points at");
            return;
        }
        Vec3 eyes = start.camera().getEyePosition(TICK_PARTIAL_TICK);
        Vec3 sightEnd = eyes.add(start.camera().getViewVector(TICK_PARTIAL_TICK).scale(reach));
        boolean inSight = target.getBoundingBox().inflate(target.getPickRadius()).clip(eyes, sightEnd).isPresent();
        String uncertainty = this.uncertainBreakOnSight(start);
        if (inSight && crosshair.getType() != HitResult.Type.MISS) {
            this.rejectUnlessUncertain(action, Check.HITBOX, action.description() + " behind " + describeCrosshair(start, false) + ", which the crosshair pointed at",
                    uncertainty);
        } else {
            this.rejectUnlessUncertain(action, Check.HITBOX, action.description() + ", which the crosshair did not point at: it pointed at "
                    + describeCrosshair(start, false), uncertainty);
        }
    }

    // - The action's block face was not one the crosshair pointed at: the block lay out of the player's block -
    // - interaction range (Player.isWithinBlockInteractionRange, which Minecraft.pick's reach matches), or within it but -
    // - the crosshair pointed elsewhere, which is unknown in a minecart that may have turned the player (see -
    // - passengerTurnUnknown) and where the client may have broken a block on the sight line otherwise than the -
    // - sandbox -
    private void rejectBlockMiss(CheckedAction action, SandboxPlayer player, BlockPos pos, KeyHandlingStart start, String face) {
        if (!player.isWithinBlockInteractionRange(pos, 0.0)) {
            this.rejectOutOfReach(action, Math.sqrt(new AABB(pos).distanceToSqr(player.getEyePosition())), player.blockInteractionRange(), "the player");
        } else if (passengerTurnUnknown(player)) {
            this.tickPackets.notes.add("not checked: " + action.description() + " with a rotation the minecart may have turned");
        } else {
            this.rejectUnlessUncertain(action, Check.HITBOX, action.description() + " at " + face + ", which the crosshair did not point at: it pointed at "
                    + describeCrosshair(start, true), this.uncertainBreakOnSight(start));
        }
    }

    // - A block the client may have broken otherwise than the sandbox (SandboxGameMode.uncertainBreaks) on the -
    // - camera's sight line as far as the pick looked, so that the client's crosshair may have met another block or -
    // - entity than the sandbox's; null when there is none. A break takes a second half along (a door, a bed, a tall -
    // - plant), which lies next to the block -
    private @Nullable String uncertainBreakOnSight(KeyHandlingStart start) {
        List<SandboxGameMode.UncertainBreak> uncertainBreaks = this.gameMode.uncertainBreaks();
        if (uncertainBreaks.isEmpty()) {
            return null;
        }
        Vec3 eyes = start.camera().getEyePosition(TICK_PARTIAL_TICK);
        Vec3 sightEnd = eyes.add(start.camera().getViewVector(TICK_PARTIAL_TICK).scale(start.pickReach()));
        for (SandboxGameMode.UncertainBreak uncertain : uncertainBreaks) {
            AABB around = new AABB(uncertain.pos()).inflate(SECOND_HALF_REACH);
            if (around.contains(eyes) || around.clip(eyes, sightEnd).isPresent()) {
                return describeUncertainBreak(uncertain);
            }
        }
        return null;
    }

    private static String describeUncertainBreak(SandboxGameMode.UncertainBreak uncertain) {
        return "the client may have broken the block at " + uncertain.pos().toShortString() + " otherwise than the sandbox, starting with another hotbar item";
    }

    // - How far along the camera's sight line Minecraft.pick looks for a player holding out this item: as far as the -
    // - block and entity interaction ranges reach, or the item's attack range (SandboxPlayer.raycastHitResult, -
    // - AttackRange.getClosesetHit) -
    private static double pickReach(SandboxPlayer player, ItemStack heldOut) {
        double reach = Math.max(player.blockInteractionRange(), player.entityInteractionRange());
        AttackRange attackRange = heldOut.get(DataComponents.ATTACK_RANGE);
        return attackRange == null ? reach : Math.max(reach, attackRange.effectiveMaxRange(player) + attackRange.hitboxMargin());
    }

    // - The slot the tick's hotbar keys selected, when the key handling reported it after the tick's first packet (see -
    // - performTickActions): the first report there, unless an action that reports the slot itself came before it, -
    // - after which no vanilla client reports one (see checkHotbarReport); -1 when there is none -
    private static int keyHandlingReport(List<Packet<?>> actions) {
        for (int index = 0; index < actions.size(); index++) {
            Packet<?> action = actions.get(index);
            if (index > 0 && action instanceof ServerboundSetCarriedItemPacket) {
                return index;
            }
            if (reportsCarriedItem(action)) {
                return -1;
            }
        }
        return -1;
    }

    // - The packets MultiPlayerGameMode sends right after ensureHasSentCarriedItem, which reports a slot the hotbar -
    // - keys selected before them: dropping and releasing an item, stabbing, finishing or turning while breaking -
    // - (continueDestroyBlock), attacking, interacting and using an item -
    private static boolean reportsCarriedItem(Packet<?> packet) {
        return switch (packet) {
            case ServerboundPlayerActionPacket playerAction -> switch (playerAction.getAction()) {
                case DROP_ITEM, DROP_ALL_ITEMS, RELEASE_USE_ITEM, STAB, STOP_DESTROY_BLOCK, CHANGE_DESTROY_DIRECTION -> true;
                case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, SWAP_ITEM_WITH_OFFHAND -> false;
            };
            case ServerboundAttackPacket ignored -> true;
            case ServerboundInteractPacket ignored -> true;
            case ServerboundUseItemOnPacket ignored -> true;
            case ServerboundUseItemPacket ignored -> true;
            default -> false;
        };
    }

    // - A hotbar slot the tick reports after its first packet. Only the hotbar keys at the start of -
    // - Minecraft.handleKeybinds change the slot during a tick, and the first action that reports it does so before -
    // - itself (see reportsCarriedItem), so a vanilla client reports at most one slot there, and none after such an -
    // - action -
    private void checkHotbarReport(ServerboundSetCarriedItemPacket report, int index, long packet) {
        if (index == 0) {
            return;
        }
        List<Packet<?>> actions = this.tickPackets.actions;
        String after = null;
        for (int earlier = 0; earlier < index && after == null; earlier++) {
            Packet<?> earlierAction = actions.get(earlier);
            if (earlier > 0 && earlierAction instanceof ServerboundSetCarriedItemPacket earlierReport) {
                after = "after it had reported slot " + (earlierReport.getSlot() + 1);
            } else if (reportsCarriedItem(earlierAction)) {
                after = "after its " + describeAction(earlierAction);
            }
        }
        if (after != null) {
            CheckedAction selection = new CheckedAction("selected hotbar slot " + (report.getSlot() + 1), packet, Flag.NO_PREDICTION);
            this.reject(selection, Check.INTERACTION, selection.description() + " " + after + " in the same tick, when a vanilla client's hotbar keys only "
                    + "change the slot before its actions");
        }
    }

    private static String describeAction(Packet<?> action) {
        return action instanceof ServerboundPlayerActionPacket playerAction
                ? playerAction.getAction().name().toLowerCase(Locale.ROOT)
                : action.type().id().getPath();
    }

    // - What the crosshair pointed at, and with the item the client may have switched to already where that one -
    // - picks differently; with the face of a block when it matters -
    private static String describeCrosshair(KeyHandlingStart start, boolean withFace) {
        HitResult withSwitchedItem = start.crosshairWithSwitchedItem();
        String pointedAt = describeHit(start.crosshair(), withFace);
        return withSwitchedItem == null ? pointedAt : pointedAt + " (with the item the client switched to: " + describeHit(withSwitchedItem, withFace) + ")";
    }

    private static String describeEntity(Entity entity) {
        return EntityType.getKey(entity.getType()) + " (entity " + entity.getId() + ")";
    }

    private static String describeItem(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String describeBlock(SandboxLevel level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()) + " at " + pos.toShortString();
    }

    // - What Minecraft.pick found -
    private static String describeHit(HitResult hit, boolean withFace) {
        return switch (hit) {
            case EntityHitResult entityHit -> describeEntity(entityHit.getEntity());
            case BlockHitResult blockHit when hit.getType() == HitResult.Type.BLOCK -> withFace
                    ? "the " + blockHit.getDirection().getSerializedName() + " face of the block at " + blockHit.getBlockPos().toShortString()
                    : "the block at " + blockHit.getBlockPos().toShortString();
            default -> "nothing within reach";
        };
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

    // - MultiPlayerGameMode.useItem. A vanilla client uses an item only while it uses none (Minecraft.handleKeybinds), -
    // - so a use during a trident use the sandbox is unsure of shows that the client's never began. Where a riptide -
    // - trident's start depends on rain the client's sky light may not show yet, the sandbox is unsure of the client's -
    // - use from here on -
    private void useItem(SandboxLevel level, SandboxPlayer player, ServerboundUseItemPacket useItem) {
        UncertainTridentUse uncertain = this.uncertainTridentUse;
        if (uncertain != null) {
            this.uncertainTridentUse = null;
            if (player.isUsingItem() && ItemStack.isSameItem(player.getUseItem(), uncertain.trident())) {
                player.stopUsingItem();
                this.tickPackets.notes.add("the client's use of " + describeItem(uncertain.trident()) + " had not begun, as its next use of an item shows");
            }
        }
        ItemStack item = player.getItemInHand(useItem.hand());
        boolean rainUncertain = tridentStartDependsOnLaggingRain(level, player, item);
        ItemStack trident = item.copy();
        this.gameMode.useItem(level, player, useItem.hand(), useItem.sequence(), this.tickPackets);
        if (rainUncertain) {
            this.uncertainTridentUse = new UncertainTridentUse(useItem.hand(), trident, this.currentClientTick);
        }
    }

    // - MultiPlayerGameMode.releaseUsingItem. A vanilla client releases only an item it uses, so a release ends a -
    // - trident use the sandbox is unsure of: the client's had begun. Where a riptide trident's throw of the player -
    // - depends on rain the client's sky light may not show yet, the release with the other outcome is the -
    // - alternative, made again from a snapshot of the player taken before the release. It needs the release to be -
    // - the last thing of the tick that changes the player -
    private void releaseUsingItem(SandboxLevel level, SandboxPlayer player, boolean lastChange) {
        UncertainTridentUse uncertain = this.uncertainTridentUse;
        if (uncertain != null) {
            this.uncertainTridentUse = null;
            if (!player.isUsingItem() && ItemStack.isSameItem(player.getItemInHand(uncertain.hand()), uncertain.trident())) {
                player.startUsingItemSince(uncertain.hand(), (int) (this.currentClientTick - uncertain.startTick()));
                this.tickPackets.notes.add("the client's use of " + describeItem(uncertain.trident()) + " had begun, as its release shows");
            }
        }
        if (!tridentReleaseDependsOnLaggingRain(level, player)) {
            this.gameMode.releaseUsingItem(player);
            return;
        }
        String uncertainty = "whether rain fell on the player as it let go of the riptide trident, which the client's sky light may not have shown yet";
        StateSnapshot beforeRelease = null;
        if (lastChange && this.alternativesDisabled == null) {
            try {
                beforeRelease = this.captureState(List.of(player, level.getRandom()));
            } catch (StateSnapshot.SnapshotException problem) {
                this.disableAlternatives("the player's state before a trident release could not be saved", problem);
            }
        }
        boolean wet = player.isInWaterOrRain();
        this.gameMode.releaseUsingItem(player);
        if (beforeRelease == null) {
            this.tickPackets.uncertainties.add(uncertainty);
            return;
        }
        StateSnapshot savedBeforeRelease = beforeRelease;
        this.tickPackets.alternatives.add(new TickAlternative(uncertainty, wet ? "the trident's release without rain" : "the trident's release in rain",
                ItemStack.EMPTY, releasing -> {
                    this.restoreState(savedBeforeRelease);
                    releasing.withWaterOrRain(!wet, () -> this.gameMode.releaseUsingItem(releasing));
                }));
    }

    // - The other state of an uncertain trident use, as a change right before the player's tick: the client's use not -
    // - having begun where the sandbox's did, or having begun where the sandbox's did not, with the ticks it has run -
    // - since (LivingEntity.updatingUsingItem counts once a tick) -
    private TickAlternative otherTridentUse(UncertainTridentUse uncertain, SandboxPlayer player) {
        String uncertainty = "whether the client's use of the riptide trident began, which depended on rain its sky light may not have shown yet";
        if (player.isUsingItem() && ItemStack.isSameItem(player.getUseItem(), uncertain.trident())) {
            return new TickAlternative(uncertainty, "the trident use not having begun", ItemStack.EMPTY, SandboxPlayer::stopUsingItem);
        }
        int ticksUsed = (int) (this.currentClientTick - uncertain.startTick());
        return new TickAlternative(uncertainty, "the trident use having begun", ItemStack.EMPTY,
                using -> using.startUsingItemSince(uncertain.hand(), ticksUsed));
    }

    // - Whether TridentItem.use decides on rain the client's sky light may not show yet: a trident off cooldown (which -
    // - MultiPlayerGameMode.useItem asks first) that is not about to break and throws its user begins only where water -
    // - or rain wets the player -
    private static boolean tridentStartDependsOnLaggingRain(SandboxLevel level, SandboxPlayer player, ItemStack item) {
        return item.getItem() instanceof TridentItem && !player.getCooldowns().isOnCooldown(item) && !item.nextDamageWillBreak()
                && EnchantmentHelper.getTridentSpinAttackStrength(item, player) > 0.0F && rainDependsOnLaggingSkyLight(level, player);
    }

    // - Whether TridentItem.releaseUsing does: a trident used long enough to throw, that throws its user, who rides -
    // - nothing, and that is not about to break -
    private static boolean tridentReleaseDependsOnLaggingRain(SandboxLevel level, SandboxPlayer player) {
        ItemStack item = player.getUseItem();
        return player.isUsingItem() && item.getItem() instanceof TridentItem && player.getTicksUsingItem() >= TridentItem.THROW_THRESHOLD_TIME
                && EnchantmentHelper.getTridentSpinAttackStrength(item, player) > 0.0F && !player.isPassenger() && !item.nextDamageWillBreak()
                && rainDependsOnLaggingSkyLight(level, player);
    }

    // - Whether rain on the player (Entity.isInRain: at its feet or at the top of its box, one column) depends on sky -
    // - light the client may not have caught up with (see SandboxLevel.skyLightMayLag); water wets it regardless -
    private static boolean rainDependsOnLaggingSkyLight(SandboxLevel level, SandboxPlayer player) {
        if (player.isInWater()) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        BlockPos top = BlockPos.containing(feet.getX(), player.getBoundingBox().maxY, feet.getZ());
        return level.skyLightMayLag(feet) && (level.rainsIfSkyLit(feet) || level.rainsIfSkyLit(top));
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

    // - Returns whether the action belongs to a swing, which then needs no further interpretation. lastChange tells -
    // - whether only swings follow it in the tick (see onlySwingsFollow) -
    private boolean performPlayerAction(
            ServerboundPlayerActionPacket action, @Nullable Packet<?> next, boolean keyHandlingSlotKnown, boolean lastChange, SandboxLevel level,
            SandboxPlayer player
    ) {
        switch (action.getAction()) {
            case START_DESTROY_BLOCK -> {
                this.gameMode.startDestroyBlock(level, player, action.getPos(), action.getSequence(), keyHandlingSlotKnown, this.tickPackets);
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
                this.releaseUsingItem(level, player, lastChange);
                return false;
            }
            case STAB -> {
                this.gameMode.piercingAttack(player);
                return false;
            }
        }
        throw new IllegalArgumentException("Unknown player action " + action.getAction());
    }

    // - What the sandbox cannot know about the tick's movement beyond the tick's own packets: the items, when they -
    // - differ from the client's, and a block the client may have broken otherwise than the sandbox where the -
    // - movement went past it, or where ending the prediction of its break may have put the client's player back -
    // - (see SandboxGameMode.recentUncertainBreaks) -
    private void collectOngoingUncertainties(
            List<String> uncertainties, SandboxPlayer player, Vec3 positionBeforeTick, Entity vehicleBeforeTick, Vec3 vehiclePositionBeforeTick
    ) {
        if (this.unknownInventoryMenu.isPresent()) {
            uncertainties.add("items differ from the client's");
        }
        List<SandboxGameMode.UncertainBreak> uncertainBreaks = this.gameMode.recentUncertainBreaks();
        this.gameMode.forgetAcknowledgedUncertainBreaks();
        if (uncertainBreaks.isEmpty()) {
            return;
        }
        AABB swept = this.tickSweep(player, positionBeforeTick, vehicleBeforeTick, vehiclePositionBeforeTick);
        for (SandboxGameMode.UncertainBreak uncertain : uncertainBreaks) {
            if (swept.intersects(new AABB(uncertain.pos()).inflate(SECOND_HALF_REACH))) {
                uncertainties.add(describeUncertainBreak(uncertain));
                return;
            }
        }
    }

    // - Where the player and the vehicle it rode went during the tick: their boxes before the tick, after the -
    // - sandbox's tick and where the client reported them, grown by the blocks beside them that still change a -
    // - movement -
    private AABB tickSweep(SandboxPlayer player, Vec3 positionBeforeTick, Entity vehicleBeforeTick, Vec3 vehiclePositionBeforeTick) {
        EntityDimensions dimensions = player.getDimensions(player.getPose());
        AABB swept = player.getBoundingBox().minmax(dimensions.makeBoundingBox(positionBeforeTick));
        ServerboundMovePlayerPacket movePacket = this.tickPackets.movePacket;
        if (movePacket != null && movePacket.hasPosition()) {
            swept = swept.minmax(dimensions.makeBoundingBox(movePacket.getX(0.0), movePacket.getY(0.0), movePacket.getZ(0.0)));
        }
        if (vehicleBeforeTick != player) {
            EntityDimensions vehicleDimensions = vehicleBeforeTick.getDimensions(vehicleBeforeTick.getPose());
            swept = swept.minmax(vehicleBeforeTick.getBoundingBox()).minmax(vehicleDimensions.makeBoundingBox(vehiclePositionBeforeTick));
            ServerboundMoveVehiclePacket vehicleMove = this.tickPackets.vehicleMove;
            if (vehicleMove != null) {
                swept = swept.minmax(vehicleDimensions.makeBoundingBox(vehicleMove.movingTo().position()));
            }
        }
        return swept.inflate(MOVEMENT_BLOCK_REACH);
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
                Double.NaN, null, this.tickStart, List.copyOf(rejections), notes
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
                offset, null, this.tickStart, verdict.flags(), notes
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
                Double.NaN, vehicleState, this.tickStart, verdict.flags(), notes
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
