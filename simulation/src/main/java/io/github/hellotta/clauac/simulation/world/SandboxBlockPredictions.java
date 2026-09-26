package io.github.hellotta.clauac.simulation.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

// - Port of the client-only BlockStatePredictionHandler. While the client predicts the result of a block -
// - interaction, the server's block updates for the positions it changed are kept aside and only applied once the -
// - server acknowledges the interaction's sequence number. The sandbox receives the sequence number the client used -
// - with every predicting packet and adopts it, so both count in step -
public final class SandboxBlockPredictions implements AutoCloseable {

    private final Long2ObjectOpenHashMap<ServerVerifiedState> serverVerifiedStates = new Long2ObjectOpenHashMap<>();
    private int currentSequenceNr;
    private boolean isPredicting;
    private int lastTeleportSequence = -1;

    public void retainKnownServerState(BlockPos pos, BlockState state, Vec3 playerPosition) {
        this.serverVerifiedStates.compute(
                pos.asLong(),
                (key, serverVerifiedState) -> serverVerifiedState != null
                        ? serverVerifiedState.setSequence(this.currentSequenceNr)
                        : new ServerVerifiedState(this.currentSequenceNr, state, playerPosition)
        );
    }

    public boolean updateKnownServerState(BlockPos pos, BlockState blockState) {
        ServerVerifiedState serverVerifiedState = this.serverVerifiedStates.get(pos.asLong());
        if (serverVerifiedState == null) {
            return false;
        }

        serverVerifiedState.setBlockState(blockState);
        return true;
    }

    public void endPredictionsUpTo(int sequence, SandboxLevel level) {
        ObjectIterator<Long2ObjectMap.Entry<ServerVerifiedState>> stateIterator = this.serverVerifiedStates.long2ObjectEntrySet().iterator();

        while (stateIterator.hasNext()) {
            Long2ObjectMap.Entry<ServerVerifiedState> next = stateIterator.next();
            ServerVerifiedState serverVerifiedState = next.getValue();
            if (serverVerifiedState.sequence <= sequence) {
                BlockPos pos = BlockPos.of(next.getLongKey());
                stateIterator.remove();
                level.syncBlockState(pos, serverVerifiedState.blockState, this.lastTeleportSequence < sequence ? serverVerifiedState.playerPos : null);
            }
        }
    }

    // - The client increments its sequence number for every prediction and sends it; returns whether the sandbox -
    // - had counted to the same number -
    public boolean startPredicting(int clientSequence) {
        boolean inStep = this.currentSequenceNr + 1 == clientSequence;
        this.currentSequenceNr = clientSequence;
        this.isPredicting = true;
        return inStep;
    }

    @Override
    public void close() {
        this.isPredicting = false;
    }

    public void onTeleport() {
        this.lastTeleportSequence = this.currentSequenceNr;
    }

    public boolean isPredicting() {
        return this.isPredicting;
    }

    public boolean hasPendingPredictions() {
        return !this.serverVerifiedStates.isEmpty();
    }

    private static final class ServerVerifiedState {

        private final Vec3 playerPos;
        private int sequence;
        private BlockState blockState;

        private ServerVerifiedState(int sequence, BlockState blockState, Vec3 playerPos) {
            this.sequence = sequence;
            this.blockState = blockState;
            this.playerPos = playerPos;
        }

        private ServerVerifiedState setSequence(int sequence) {
            this.sequence = sequence;
            return this;
        }

        private void setBlockState(BlockState blockState) {
            this.blockState = blockState;
        }
    }
}
