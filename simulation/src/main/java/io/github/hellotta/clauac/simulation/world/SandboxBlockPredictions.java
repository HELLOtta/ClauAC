package io.github.hellotta.clauac.simulation.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

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

    // - What the client keeps aside for a position while it predicts a change there: the number of the last -
    // - prediction that changed it, the server's block there, and where the player stood when the first one did -
    public record RetainedState(int sequence, BlockState blockState, Vec3 playerPos) {
    }

    // - Everything kept aside now, by position -
    public Map<BlockPos, RetainedState> allRetained() {
        Map<BlockPos, RetainedState> all = new LinkedHashMap<>();
        for (Long2ObjectMap.Entry<ServerVerifiedState> entry : this.serverVerifiedStates.long2ObjectEntrySet()) {
            ServerVerifiedState state = entry.getValue();
            all.put(BlockPos.of(entry.getLongKey()), new RetainedState(state.sequence, state.blockState, state.playerPos));
        }
        return all;
    }

    // - What is kept aside for the position, or null -
    public @Nullable RetainedState retained(BlockPos pos) {
        ServerVerifiedState state = this.serverVerifiedStates.get(pos.asLong());
        return state != null ? new RetainedState(state.sequence, state.blockState, state.playerPos) : null;
    }

    // - Keeps this aside for the position instead, or nothing for null: the predictions of another world the client -
    // - may have been in (see PlayConnection) -
    public void putRetained(BlockPos pos, @Nullable RetainedState retained) {
        if (retained == null) {
            this.serverVerifiedStates.remove(pos.asLong());
        } else {
            this.serverVerifiedStates.put(pos.asLong(), new ServerVerifiedState(retained.sequence(), retained.blockState(), retained.playerPos()));
        }
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
