package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.world.SandboxBlockPredictions;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

// - A start of breaking made with an item the sandbox does not know (see SandboxGameMode.startDestroyBlock) where -
// - another hotbar item would have left the client in another world than the sandbox's: one with other blocks, which -
// - the client keeps predicted until the server acknowledges the start, or with another mining state. The client is -
// - in the sandbox's world or in one of these others until its packets tell which (see PlayConnection), and the -
// - server's acknowledgement and block updates bring the blocks of the worlds together again (see -
// - SandboxGameMode.onBlockChangedAck and onServerBlockState) -
final class UncertainStart {

    private final BlockPos pos;
    private final int sequence;
    private final List<OtherWorld> others;

    UncertainStart(BlockPos pos, int sequence, List<OtherWorld> others) {
        this.pos = pos;
        this.sequence = sequence;
        this.others = new ArrayList<>(others);
    }

    BlockPos pos() {
        return this.pos;
    }

    int sequence() {
        return this.sequence;
    }

    // - The worlds besides the sandbox's that the client may be in, which evidence and convergence take away -
    List<OtherWorld> others() {
        return this.others;
    }

    // - A world the start leaves the client in with another hotbar item: the item (one of those that do the same), -
    // - the blocks that differ from the sandbox's with the block entities they have there, what the client keeps -
    // - aside there where that differs from the sandbox (null for nothing kept aside), its mining state, and the -
    // - columns whose lowest sky light source its break moved, at the level tick when it did -
    static final class OtherWorld {

        private final ItemStack item;
        private final Map<BlockPos, SandboxLevel.BlockSnapshot> blocks;
        private final Map<BlockPos, SandboxBlockPredictions.RetainedState> retained;
        private final List<BlockPos> retainedNothing;
        private final SandboxGameMode.MiningState mining;
        private final List<BlockPos> movedSkyLightColumns;
        private final long skyLightMoveTick;

        OtherWorld(
                ItemStack item, Map<BlockPos, SandboxLevel.BlockSnapshot> blocks, Map<BlockPos, SandboxBlockPredictions.RetainedState> retained,
                List<BlockPos> retainedNothing, SandboxGameMode.MiningState mining, List<BlockPos> movedSkyLightColumns, long skyLightMoveTick
        ) {
            this.item = item;
            this.blocks = blocks;
            this.retained = retained;
            this.retainedNothing = new ArrayList<>(retainedNothing);
            this.mining = mining;
            this.movedSkyLightColumns = List.copyOf(movedSkyLightColumns);
            this.skyLightMoveTick = skyLightMoveTick;
        }

        ItemStack item() {
            return this.item;
        }

        // - The blocks that differ from the sandbox's; the map is the world's own and changes with the server's blocks -
        Map<BlockPos, SandboxLevel.BlockSnapshot> blocks() {
            return this.blocks;
        }

        // - What the client keeps aside in this world where the sandbox keeps something else aside or nothing -
        Map<BlockPos, SandboxBlockPredictions.RetainedState> retained() {
            return this.retained;
        }

        // - The positions where the client keeps nothing aside in this world while the sandbox keeps something aside -
        List<BlockPos> retainedNothing() {
            return this.retainedNothing;
        }

        SandboxGameMode.MiningState mining() {
            return this.mining;
        }

        List<BlockPos> movedSkyLightColumns() {
            return this.movedSkyLightColumns;
        }

        long skyLightMoveTick() {
            return this.skyLightMoveTick;
        }
    }
}
