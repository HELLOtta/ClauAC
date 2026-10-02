package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.api.SimulationStatistics;
import java.util.Arrays;

// - What the simulation of one connection costs: how long its tasks run and wait, how long each client tick took, -
// - and what the snapshots of the player's state took. Updated by the connection's tasks, which run one at a time -
// - (see SerialExecutor), and read from any thread through statistics -
final class SimulationCost {

    // - One minute of client ticks, for the recent median and 99th percentile -
    private static final int RECENT_TICKS = 1200;
    private static final double MEDIAN = 0.5;
    private static final double PERCENTILE_99 = 0.99;

    private final long startedAt = System.nanoTime();
    // - Written only by the connection's tasks; volatile for the readers of statistics -
    private volatile long busyNanos;
    private volatile long maxLagNanos;
    private volatile long ticks;
    private volatile long maxTickNanos;
    private volatile long snapshots;
    private volatile long snapshotNanos;
    private volatile int maxSnapshotObjects;
    // - The times of the recent ticks as a ring buffer, guarded by the array itself -
    private final long[] recentTickNanos = new long[RECENT_TICKS];
    private int recentCount;
    private int recentNext;
    // - Only for the connection's tasks: when the running task started, up to when its time went into a tick, and the -
    // - time of the current tick so far -
    private long taskStartedAt;
    private long countedUpTo;
    private long currentTickNanos;

    void taskStarted(long now, long waitedNanos) {
        this.taskStartedAt = now;
        this.countedUpTo = now;
        if (waitedNanos > this.maxLagNanos) {
            this.maxLagNanos = waitedNanos;
        }
    }

    void taskFinished(long now) {
        this.busyNanos += now - this.taskStartedAt;
        this.currentTickNanos += now - this.countedUpTo;
    }

    // - Ends the client tick the running task simulated and returns its time: what the tasks since the previous tick -
    // - took, this one up to now. The rest of the running task counts for the next tick -
    long endTick(long now) {
        long tickNanos = this.currentTickNanos + now - this.countedUpTo;
        this.currentTickNanos = 0L;
        this.countedUpTo = now;
        this.ticks++;
        if (tickNanos > this.maxTickNanos) {
            this.maxTickNanos = tickNanos;
        }
        synchronized (this.recentTickNanos) {
            this.recentTickNanos[this.recentNext] = tickNanos;
            this.recentNext = (this.recentNext + 1) % RECENT_TICKS;
            this.recentCount = Math.min(this.recentCount + 1, RECENT_TICKS);
        }
        return tickNanos;
    }

    void snapshotTaken(long nanos, int objects) {
        this.snapshots++;
        this.snapshotNanos += nanos;
        if (objects > this.maxSnapshotObjects) {
            this.maxSnapshotObjects = objects;
        }
    }

    void snapshotRestored(long nanos) {
        this.snapshotNanos += nanos;
    }

    SimulationStatistics statistics(int queuedPackets, long queuedBytes, long lagNanos, int unconfirmedPackets, long unconfirmedBytes) {
        long[] recent;
        synchronized (this.recentTickNanos) {
            recent = Arrays.copyOf(this.recentTickNanos, this.recentCount);
        }
        Arrays.sort(recent);
        return new SimulationStatistics(
                System.nanoTime() - this.startedAt, this.busyNanos, this.ticks, this.maxTickNanos,
                recent.length, percentile(recent, MEDIAN), percentile(recent, PERCENTILE_99),
                queuedPackets, queuedBytes, lagNanos, Math.max(this.maxLagNanos, lagNanos),
                unconfirmedPackets, unconfirmedBytes,
                this.snapshots, this.snapshotNanos, this.maxSnapshotObjects
        );
    }

    // - The nearest-rank percentile of sorted values, 0 without any -
    private static long percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0L;
        }
        int rank = (int) Math.ceil(fraction * sorted.length);
        return sorted[Math.max(rank, 1) - 1];
    }
}
