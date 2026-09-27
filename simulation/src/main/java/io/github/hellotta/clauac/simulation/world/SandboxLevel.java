package io.github.hellotta.clauac.simulation.world;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.crafting.RecipeAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.entity.LevelCallback;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.TransientEntitySectionManager;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.ticks.BlackholeTickAccess;
import net.minecraft.world.ticks.LevelTickAccess;
import org.jspecify.annotations.Nullable;

// - Port of the client-only ClientLevel as far as it affects how the local player moves: it is a client-side level -
// - (isClientSide), so vanilla code takes the client's branches and never runs server-only logic. Every entity the -
// - server shows the client lives and ticks here as on the client, so pushes, collisions and vehicles act on the -
// - simulated player. What the client renders, plays or shows (particles, sounds, block breaking progress, tints, -
// - sky) has no effect here -
public final class SandboxLevel extends Level {

    // - The client catches its sky light up with its blocks only once a frame (Minecraft.renderFrame calls -
    // - ClientLevel.update, which runs the light engine's updates), and a frame comes at least every ten client ticks -
    // - (Minecraft.runTick runs at most ten before it renders). The light data of a chunk and the server's updates of -
    // - it wait in a queue, of which each frame runs a tenth, at least ten, and from 1000 on all -
    // - (ClientLevel.pollLightUpdates). Until then the client's sky light may show a column as it was before its blocks -
    // - changed, or a chunk that has just come without light. SKY_LIGHT_SETTLE_TICKS is how long that takes at most -
    // - after the change or the chunk: the frames a queue just short of 1000 takes, ten ticks each. The server lights -
    // - a change within its next ticks, so its update reaches the client well within that time -
    private static final int MAX_TICKS_PER_FRAME = 10;
    private static final int LIGHT_QUEUE_RUN_AT_ONCE = 1000;
    private static final int LIGHT_QUEUE_LEAST_PER_FRAME = 10;
    private static final int LIGHT_QUEUE_SHARE_PER_FRAME = 10;
    public static final long SKY_LIGHT_SETTLE_TICKS = (long) framesToRunLightQueue(LIGHT_QUEUE_RUN_AT_ONCE - 1) * MAX_TICKS_PER_FRAME;
    private static final long NEVER = Long.MIN_VALUE;

    private final EntityTickList tickingEntities = new EntityTickList();
    private final TransientEntitySectionManager<Entity> entityStorage = new TransientEntitySectionManager<>(Entity.class, new EntityCallbacks());
    private final SandboxLevelData clientLevelData;
    private final SandboxBlockPredictions blockPredictions = new SandboxBlockPredictions();
    private final TickRateManager tickRateManager = new TickRateManager();
    private final List<Player> players = new ArrayList<>();
    private final List<EnderDragonPart> dragonParts = new ArrayList<>();
    private final SandboxChunkSource chunkSource;
    private final WorldBorder worldBorder = new WorldBorder();
    private final SandboxClockManager clockManager;
    private final Scoreboard scoreboard;
    // - ClientLevel.recipeAccess asks the connection, whose recipes outlive the level -
    private final Supplier<RecipeAccess> recipes;
    private final FeatureFlagSet enabledFeatures;
    private final EnvironmentAttributeSystem environmentAttributes;
    private final int seaLevel;
    private int serverSimulationDistance;
    private @Nullable Player localPlayer;
    // - Ticks the local player at its place among the entities instead of tickNonPassenger (see setLocalPlayerTick) -
    private @Nullable Consumer<Entity> localPlayerTick;
    // - Client ticks this level ran (see tick), the clock of the two maps below: when each chunk the client has -
    // - arrived, by ChunkPos.pack, and when the lowest sky light source of each column last moved, by columnKey. -
    // - Entries older than SKY_LIGHT_SETTLE_TICKS no longer matter and are dropped every SKY_LIGHT_SETTLE_TICKS -
    private long ticks;
    private final Long2LongOpenHashMap chunkArrivals = new Long2LongOpenHashMap();
    private final Long2LongOpenHashMap skyLightSourceMoves = new Long2LongOpenHashMap();

    // - clockManager and scoreboard belong to the connection and outlive levels, like on the client -
    public SandboxLevel(
            SandboxLevelData levelData,
            ResourceKey<Level> dimension,
            RegistryAccess registryAccess,
            Holder<DimensionType> dimensionType,
            int serverChunkRadius,
            int serverSimulationDistance,
            boolean isDebug,
            long biomeZoomSeed,
            int seaLevel,
            SandboxClockManager clockManager,
            Scoreboard scoreboard,
            Supplier<RecipeAccess> recipes,
            FeatureFlagSet enabledFeatures
    ) {
        super(levelData, dimension, registryAccess, dimensionType, true, isDebug, biomeZoomSeed, 1000000);
        this.clientLevelData = levelData;
        this.clockManager = clockManager;
        this.scoreboard = scoreboard;
        this.recipes = recipes;
        this.enabledFeatures = enabledFeatures;
        this.chunkSource = new SandboxChunkSource(this, serverChunkRadius);
        this.seaLevel = seaLevel;
        this.setRespawnData(LevelData.RespawnData.of(dimension, new BlockPos(8, 64, 8), 0.0F, 0.0F));
        this.serverSimulationDistance = serverSimulationDistance;
        // - The client adds two sky flash layers on top of the default ones; they only change sky colors -
        this.environmentAttributes = EnvironmentAttributeSystem.builder().addDefaultLayers(this).build();
        this.chunkArrivals.defaultReturnValue(NEVER);
        this.skyLightSourceMoves.defaultReturnValue(NEVER);
    }

    // - The frames ClientLevel.pollLightUpdates takes to run a queue of this many light updates -
    private static int framesToRunLightQueue(int queued) {
        int frames = 0;
        for (int left = queued; left > 0; left -= left < LIGHT_QUEUE_RUN_AT_ONCE ? Math.max(LIGHT_QUEUE_LEAST_PER_FRAME, left / LIGHT_QUEUE_SHARE_PER_FRAME) : left) {
            frames++;
        }
        return frames;
    }

    // - The player whose movement is simulated; the client's equivalent is Minecraft.player -
    public void setLocalPlayer(@Nullable Player localPlayer) {
        this.localPlayer = localPlayer;
    }

    // - Hands the local player's tick to the connection while the player rides nothing, so that it can run the tick -
    // - again from a saved start before the entities after the player tick; null goes back to tickNonPassenger -
    public void setLocalPlayerTick(@Nullable Consumer<Entity> localPlayerTick) {
        this.localPlayerTick = localPlayerTick;
    }

    // - ClientLevel.tick without the renderer's sky, particle and sound handling -
    public void tick() {
        this.ticks++;
        if (this.ticks % SKY_LIGHT_SETTLE_TICKS == 0L) {
            this.chunkArrivals.values().removeIf((long arrival) -> !this.settling(arrival));
            this.skyLightSourceMoves.values().removeIf((long move) -> !this.settling(move));
        }
        if (this.tickRateManager().runsNormally()) {
            this.getWorldBorder().tick();
            this.tickTime();
        }
        this.environmentAttributes().invalidateTickCache();
    }

    private void tickTime() {
        long gameTime = this.clientLevelData.getGameTime() + 1L;
        this.clientLevelData.setGameTime(gameTime);
        this.clockManager().tick(gameTime);
    }

    public void setTimeFromServer(long gameTime) {
        this.clientLevelData.setGameTime(gameTime);
    }

    public void tickEntities() {
        this.tickingEntities.forEach(entity -> {
            if (!entity.isRemoved() && !entity.isPassenger() && !this.tickRateManager.isEntityFrozen(entity)) {
                Consumer<Entity> localTick = this.localPlayerTick;
                this.guardEntityTick(entity == this.localPlayer && localTick != null ? localTick : this::tickNonPassenger, entity);
            }
        });
    }

    // - Whether a block entity next to this area moves entities when the block entities tick after the entities: a -
    // - piston's moving block, or a shulker box whose lid is moving -
    public boolean hasEntityMovingBlockEntityNear(AABB area) {
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(area.minX, area.minY, area.minZ), BlockPos.containing(area.maxX, area.maxY, area.maxZ))) {
            BlockEntity blockEntity = this.getBlockEntity(pos);
            if (blockEntity instanceof PistonMovingBlockEntity
                    || blockEntity instanceof ShulkerBoxBlockEntity shulkerBox
                    && shulkerBox.getAnimationStatus() != ShulkerBoxBlockEntity.AnimationStatus.CLOSED
                    && shulkerBox.getAnimationStatus() != ShulkerBoxBlockEntity.AnimationStatus.OPENED) {
                return true;
            }
        }
        return false;
    }

    public boolean isTickingEntity(Entity entity) {
        return this.tickingEntities.contains(entity);
    }

    @Override
    public boolean shouldTickDeath(Entity entity) {
        return this.localPlayer != null
                && entity.chunkPosition().getChessboardDistance(this.localPlayer.chunkPosition()) <= this.serverSimulationDistance;
    }

    public void tickNonPassenger(Entity entity) {
        entity.commonTick();
        entity.tick();

        for (Entity passenger : entity.getPassengers()) {
            this.tickPassenger(entity, passenger);
        }
    }

    private void tickPassenger(Entity vehicle, Entity entity) {
        if (entity.isRemoved() || entity.getVehicle() != vehicle) {
            entity.stopRiding();
        } else if (entity instanceof Player || this.tickingEntities.contains(entity)) {
            entity.commonTick();
            entity.rideTick();

            for (Entity passenger : entity.getPassengers()) {
                this.tickPassenger(entity, passenger);
            }
        }
    }

    public void unload(LevelChunk levelChunk) {
        levelChunk.clearAllBlockEntities();
        this.chunkSource.getLightEngine().setLightEnabled(levelChunk.getPos(), false);
        this.entityStorage.stopTicking(levelChunk.getPos());
    }

    public void onChunkLoaded(ChunkPos pos) {
        this.entityStorage.startTicking(pos);
        this.chunkArrivals.put(pos.pack(), this.ticks);
    }

    // - Deprecated in vanilla, but the client still overrides it: every chunk counts as present, which for example -
    // - keeps gravity active in LivingEntity.travelInAir while the chunk below the player has not arrived yet -
    @Deprecated
    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return true;
    }

    public void addEntity(Entity entity) {
        this.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        this.entityStorage.addEntity(entity);
    }

    public void removeEntity(int id, Entity.RemovalReason reason) {
        Entity entity = this.getEntities().get(id);
        if (entity != null) {
            entity.setRemoved(reason);
            entity.onClientRemoval();
        }
    }

    // - Only the local player can be pushed on the client; every other entity moves as the server says -
    @Override
    public List<Entity> getPushableEntities(Entity pusher, AABB boundingBox) {
        Player player = this.localPlayer;
        return player != null && player != pusher && player.getBoundingBox().intersects(boundingBox) && EntitySelector.pushableBy(pusher).test(player)
                ? List.of(player)
                : List.of();
    }

    @Override
    public @Nullable Entity getEntity(int id) {
        return this.getEntities().get(id);
    }

    public SandboxBlockPredictions getBlockPredictions() {
        return this.blockPredictions;
    }

    public void handleBlockChangedAck(int sequence) {
        this.blockPredictions.endPredictionsUpTo(sequence, this);
    }

    // - The server's block changes wait while the client predicts something else at the same position. Like -
    // - ClientLevel's, they bypass the prediction bookkeeping of setBlock -
    public void setServerVerifiedBlockState(BlockPos pos, BlockState blockState, @Block.UpdateFlags int updateFlag) {
        if (!this.blockPredictions.updateKnownServerState(pos, blockState)) {
            int sourceBefore = this.lowestSkyLightSource(pos);
            if (super.setBlock(pos, blockState, updateFlag, 512)) {
                this.noteSkyLightSourceMove(pos, sourceBefore);
            }
        }
    }

    // - Ending a prediction the server did not confirm can put the player back where it stood when it predicted -
    public void syncBlockState(BlockPos pos, BlockState state, @Nullable Vec3 playerPos) {
        BlockState oldState = this.getBlockState(pos);
        if (oldState != state) {
            this.setBlock(pos, state, 19);
            Player player = this.localPlayer;
            if (playerPos != null && player != null && this == player.level() && player.isColliding(pos, state)) {
                player.absSnapTo(playerPos.x, playerPos.y, playerPos.z);
            }
        }
    }

    // - Every block change of the level but the server's (see setServerVerifiedBlockState) goes through here -
    @Override
    public boolean setBlock(BlockPos pos, BlockState blockState, @Block.UpdateFlags int updateFlags, int updateLimit) {
        int sourceBefore = this.lowestSkyLightSource(pos);
        boolean success = this.setBlockAndRetainPrediction(pos, blockState, updateFlags, updateLimit);
        if (success) {
            this.noteSkyLightSourceMove(pos, sourceBefore);
        }
        return success;
    }

    // - A block change that moved its column's lowest sky light source (LevelChunk.setBlockState) is noted for -
    // - skyLightMayLag -
    private void noteSkyLightSourceMove(BlockPos pos, int sourceBefore) {
        if (this.lowestSkyLightSource(pos) != sourceBefore) {
            this.skyLightSourceMoves.put(columnKey(pos), this.ticks);
        }
    }

    private boolean setBlockAndRetainPrediction(BlockPos pos, BlockState blockState, @Block.UpdateFlags int updateFlags, int updateLimit) {
        if (this.blockPredictions.isPredicting()) {
            BlockState oldState = this.getBlockState(pos);
            boolean success = super.setBlock(pos, blockState, updateFlags, updateLimit);
            if (success) {
                Player player = this.localPlayer;
                if (player == null) {
                    throw new IllegalStateException("The client predicted a block change without a player");
                }
                this.blockPredictions.retainKnownServerState(pos, oldState, player.position());
            }

            return success;
        }
        return super.setBlock(pos, blockState, updateFlags, updateLimit);
    }

    public void setServerSimulationDistance(int serverSimulationDistance) {
        this.serverSimulationDistance = serverSimulationDistance;
    }

    public SandboxLevelData getSandboxLevelData() {
        return this.clientLevelData;
    }

    @Override
    public void sendBlockUpdated(BlockPos pos, BlockState old, BlockState current, @Block.UpdateFlags int updateFlags) {
        // - The client only tells its renderer about the change -
    }

    // - The only packet level code sends is the paddle state of a boat the local player steers; the real client sent -
    // - its own, and the paddles only animate the boat -
    @Override
    public void sendPacketToServer(Packet<?> packet) {
    }

    @Override
    public void playSeededSound(
            @Nullable Entity except, double x, double y, double z, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed
    ) {
        // - Sounds have no effect on movement -
    }

    @Override
    public void playSeededSound(
            @Nullable Entity except, Entity sourceEntity, Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed
    ) {
        // - Sounds have no effect on movement -
    }

    @Override
    public void explode(
            @Nullable Entity source,
            @Nullable DamageSource damageSource,
            @Nullable ExplosionDamageCalculator damageCalculator,
            double x,
            double y,
            double z,
            float r,
            boolean fire,
            Level.ExplosionInteraction interactionType,
            ParticleOptions smallExplosionParticles,
            ParticleOptions largeExplosionParticles,
            WeightedList<ExplosionParticleInfo> secondaryParticles,
            Holder<SoundEvent> explosionSound
    ) {
        // - Explosions happen on the server; the client ignores this call too -
    }

    @Override
    public String gatherChunkSourceStats() {
        return "Chunks[C] W: " + this.chunkSource.gatherStats() + " E: " + this.entityStorage.gatherStats();
    }

    @Override
    public void setRespawnData(LevelData.RespawnData respawnData) {
        this.clientLevelData.setSpawn(respawnData);
    }

    @Override
    public LevelData.RespawnData getRespawnData() {
        return this.clientLevelData.getRespawnData();
    }

    @Override
    public List<EnderDragonPart> dragonParts() {
        return this.dragonParts;
    }

    @Override
    public TickRateManager tickRateManager() {
        return this.tickRateManager;
    }

    @Override
    public @Nullable MapItemSavedData getMapData(MapId id) {
        // - Map contents are not tracked; maps do not influence movement -
        return null;
    }

    @Override
    public void destroyBlockProgress(int id, BlockPos pos, int progress) {
        // - The client only renders the cracks -
    }

    @Override
    public Scoreboard getScoreboard() {
        return this.scoreboard;
    }

    @Override
    public RecipeAccess recipeAccess() {
        return this.recipes.get();
    }

    @Override
    protected LevelEntityGetter<Entity> getEntities() {
        return this.entityStorage.getEntityGetter();
    }

    @Override
    public SandboxClockManager clockManager() {
        return this.clockManager;
    }

    @Override
    public EnvironmentAttributeSystem environmentAttributes() {
        return this.environmentAttributes;
    }

    @Override
    public FeatureFlagSet enabledFeatures() {
        return this.enabledFeatures;
    }

    @Override
    public void gameEvent(Holder<GameEvent> gameEvent, Vec3 pos, GameEvent.Context context) {
        // - Game events are server-side; the client ignores them too -
    }

    @Override
    public LevelTickAccess<Block> getBlockTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public LevelTickAccess<Fluid> getFluidTicks() {
        return BlackholeTickAccess.emptyLevelList();
    }

    @Override
    public SandboxChunkSource getChunkSource() {
        return this.chunkSource;
    }

    @Override
    public int getSeaLevel() {
        return this.seaLevel;
    }

    // - BlockAndLightGetter.canSeeSky asks whether the sky light at the position is full (15), which decides whether -
    // - rain falls there (Level.precipitationAt) and so whether a riptide trident works out of water. The sandbox keeps -
    // - no light (see SandboxChunkSource), but the client's sky light engine gives 15 exactly to the positions at or -
    // - above their column's lowest sky light source (SkyLightEngine.checkNode), which the chunk keeps up to date with -
    // - its blocks (LevelChunk.setBlockState, ChunkSkyLightSources.update). A dimension without sky light has none -
    // - anywhere, and a chunk the client does not have has no light -
    @Override
    public boolean canSeeSky(BlockPos pos) {
        return this.dimensionType().hasSkyLight() && pos.getY() >= this.lowestSkyLightSource(pos);
    }

    // - The lowest y of the column that the sky lights fully, from the chunk's sky light sources; above every y where -
    // - the client has no chunk -
    private int lowestSkyLightSource(BlockPos pos) {
        LevelChunk chunk = this.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk != null
                ? chunk.getSkyLightSources().getLowestSourceY(SectionPos.sectionRelative(pos.getX()), SectionPos.sectionRelative(pos.getZ()))
                : Integer.MAX_VALUE;
    }

    // - Whether the client's sky light at this column may not show what canSeeSky finds yet: its chunk arrived, or -
    // - its lowest sky light source moved, within SKY_LIGHT_SETTLE_TICKS -
    public boolean skyLightMayLag(BlockPos pos) {
        return this.settling(this.chunkArrivals.get(ChunkPos.pack(pos))) || this.settling(this.skyLightSourceMoves.get(columnKey(pos)));
    }

    private boolean settling(long since) {
        return since != NEVER && this.ticks - since <= SKY_LIGHT_SETTLE_TICKS;
    }

    // - Level.precipitationAt without its sky light: whether rain falls at the position if the sky lights it fully -
    public boolean rainsIfSkyLit(BlockPos pos) {
        return this.isRaining()
                && this.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos).getY() <= pos.getY()
                && this.getBiome(pos).value().getPrecipitationAt(pos, this.getSeaLevel()) == Biome.Precipitation.RAIN;
    }

    private static long columnKey(BlockPos pos) {
        return BlockPos.asLong(pos.getX(), 0, pos.getZ());
    }

    @Override
    public Holder<Biome> getUncachedNoiseBiome(int quartX, int quartY, int quartZ) {
        return this.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
    }

    @Override
    public WorldBorder getWorldBorder() {
        return this.worldBorder;
    }

    @Override
    public void levelEvent(@Nullable Entity source, int type, BlockPos pos, int data) {
        // - Level events are sounds and particles on the client -
    }

    @Override
    public List<Player> players() {
        return this.players;
    }

    private final class EntityCallbacks implements LevelCallback<Entity> {

        @Override
        public void onCreated(Entity entity) {
        }

        @Override
        public void onDestroyed(Entity entity) {
        }

        @Override
        public void onTickingStart(Entity entity) {
            SandboxLevel.this.tickingEntities.add(entity);
        }

        @Override
        public void onTickingEnd(Entity entity) {
            SandboxLevel.this.tickingEntities.remove(entity);
        }

        @Override
        public void onTrackingStart(Entity entity) {
            switch (entity) {
                case Player player -> SandboxLevel.this.players.add(player);
                case EnderDragon dragon -> SandboxLevel.this.dragonParts.addAll(Arrays.asList(dragon.getSubEntities()));
                default -> {
                }
            }
        }

        @Override
        public void onTrackingEnd(Entity entity) {
            entity.unRide();
            switch (entity) {
                case Player player -> SandboxLevel.this.players.remove(player);
                case EnderDragon dragon -> SandboxLevel.this.dragonParts.removeAll(Arrays.asList(dragon.getSubEntities()));
                default -> {
                }
            }
        }

        @Override
        public void onSectionChange(Entity entity) {
        }
    }
}
