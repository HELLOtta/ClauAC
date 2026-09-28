package io.github.hellotta.clauac.simulation.world;

import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - Port of the client-only ClientChunkCache: a ring buffer of chunks around the view center announced by the -
// - server, which answers unloaded positions with an empty chunk exactly like the client does. The renderer's -
// - bookkeeping of added and removed sections is left out. The light engine keeps no light: the only light movement -
// - code reads is whether a position sees the sky, which SandboxLevel.canSeeSky answers from the chunks' sky light -
// - sources -
public final class SandboxChunkSource extends ChunkSource {

    private static final Logger LOGGER = LogUtils.getLogger();
    private final LevelChunk emptyChunk;
    private final LevelLightEngine lightEngine;
    private volatile Storage storage;
    private final SandboxLevel level;

    SandboxChunkSource(SandboxLevel level, int serverChunkRadius) {
        this.level = level;
        this.emptyChunk = new EmptyLevelChunk(level, new ChunkPos(0, 0), level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
        this.lightEngine = new LevelLightEngine(this, false, false);
        this.storage = new Storage(calculateStorageRange(serverChunkRadius));
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.lightEngine;
    }

    private static boolean isValidChunk(@Nullable LevelChunk chunk, int x, int z) {
        if (chunk == null) {
            return false;
        }
        ChunkPos pos = chunk.getPos();
        return pos.x() == x && pos.z() == z;
    }

    public void drop(ChunkPos pos) {
        if (this.storage.inRange(pos.x(), pos.z())) {
            int index = this.storage.getIndex(pos.x(), pos.z());
            LevelChunk currentChunk = this.storage.getChunk(index);
            if (isValidChunk(currentChunk, pos.x(), pos.z())) {
                this.storage.drop(index, currentChunk);
            }
        }
    }

    @Override
    public @Nullable LevelChunk getChunk(int x, int z, ChunkStatus targetStatus, boolean loadOrGenerate) {
        if (this.storage.inRange(x, z)) {
            LevelChunk chunk = this.storage.getChunk(this.storage.getIndex(x, z));
            if (isValidChunk(chunk, x, z)) {
                return chunk;
            }
        }
        return loadOrGenerate ? this.emptyChunk : null;
    }

    @Override
    public BlockGetter getLevel() {
        return this.level;
    }

    public void replaceBiomes(int chunkX, int chunkZ, FriendlyByteBuf readBuffer) {
        if (!this.storage.inRange(chunkX, chunkZ)) {
            LOGGER.debug("Ignoring chunk since it's not in the view range: {}, {}", chunkX, chunkZ);
        } else {
            int index = this.storage.getIndex(chunkX, chunkZ);
            LevelChunk chunk = this.storage.chunks.get(index);
            if (!isValidChunk(chunk, chunkX, chunkZ)) {
                LOGGER.debug("Ignoring chunk since it's not present: {}, {}", chunkX, chunkZ);
            } else {
                chunk.replaceBiomes(readBuffer);
            }
        }
    }

    public @Nullable LevelChunk replaceWithPacketData(
            int chunkX,
            int chunkZ,
            FriendlyByteBuf readBuffer,
            Map<Heightmap.Types, long[]> heightmaps,
            Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> blockEntities) {
        if (!this.storage.inRange(chunkX, chunkZ)) {
            LOGGER.debug("Ignoring chunk since it's not in the view range: {}, {}", chunkX, chunkZ);
            return null;
        }

        int index = this.storage.getIndex(chunkX, chunkZ);
        LevelChunk chunk = this.storage.chunks.get(index);
        ChunkPos pos = new ChunkPos(chunkX, chunkZ);
        if (!isValidChunk(chunk, chunkX, chunkZ)) {
            chunk = new LevelChunk(this.level, pos);
            chunk.replaceWithPacketData(readBuffer, heightmaps, blockEntities);
            this.storage.replace(index, chunk);
        } else {
            chunk.replaceWithPacketData(readBuffer, heightmaps, blockEntities);
        }

        this.level.onChunkLoaded(pos);
        return chunk;
    }

    @Override
    public void tick(BooleanSupplier haveTime, boolean tickChunks) {
    }

    public void updateViewCenter(int x, int z) {
        this.storage.viewCenterX = x;
        this.storage.viewCenterZ = z;
    }

    public void updateViewRadius(int viewRange) {
        int chunkRadius = this.storage.chunkRadius;
        int newChunkRadius = calculateStorageRange(viewRange);
        if (chunkRadius != newChunkRadius) {
            Storage newStorage = new Storage(newChunkRadius);
            newStorage.viewCenterX = this.storage.viewCenterX;
            newStorage.viewCenterZ = this.storage.viewCenterZ;

            for (int i = 0; i < this.storage.chunks.length(); i++) {
                LevelChunk chunk = this.storage.chunks.get(i);
                if (chunk != null) {
                    ChunkPos pos = chunk.getPos();
                    if (newStorage.inRange(pos.x(), pos.z())) {
                        newStorage.replace(newStorage.getIndex(pos.x(), pos.z()), chunk);
                    }
                }
            }

            this.storage = newStorage;
        }
    }

    private static int calculateStorageRange(int viewRange) {
        return Math.max(2, viewRange) + 3;
    }

    @Override
    public String gatherStats() {
        return this.storage.chunks.length() + ", " + this.getLoadedChunksCount();
    }

    @Override
    public int getLoadedChunksCount() {
        return this.storage.chunkCount;
    }

    private final class Storage {

        private final AtomicReferenceArray<@Nullable LevelChunk> chunks;
        private final int chunkRadius;
        private final int viewRange;
        private volatile int viewCenterX;
        private volatile int viewCenterZ;
        private int chunkCount;

        private Storage(int chunkRadius) {
            this.chunkRadius = chunkRadius;
            this.viewRange = chunkRadius * 2 + 1;
            this.chunks = new AtomicReferenceArray<>(this.viewRange * this.viewRange);
        }

        private int getIndex(int chunkX, int chunkZ) {
            return Math.floorMod(chunkZ, this.viewRange) * this.viewRange + Math.floorMod(chunkX, this.viewRange);
        }

        private void replace(int index, @Nullable LevelChunk newChunk) {
            LevelChunk removedChunk = this.chunks.getAndSet(index, newChunk);
            if (removedChunk != null) {
                this.chunkCount--;
                SandboxChunkSource.this.level.unload(removedChunk);
            }

            if (newChunk != null) {
                this.chunkCount++;
            }
        }

        private void drop(int index, LevelChunk oldChunk) {
            if (this.chunks.compareAndSet(index, oldChunk, null)) {
                this.chunkCount--;
            }

            SandboxChunkSource.this.level.unload(oldChunk);
        }

        private boolean inRange(int chunkX, int chunkZ) {
            return Math.abs(chunkX - this.viewCenterX) <= this.chunkRadius && Math.abs(chunkZ - this.viewCenterZ) <= this.chunkRadius;
        }

        private @Nullable LevelChunk getChunk(int index) {
            return this.chunks.get(index);
        }
    }
}
