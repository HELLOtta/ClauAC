package io.github.hellotta.clauac.simulation.session;

// - How many ticks a client may have ended by now. A vanilla client ends one tick per tick target of real time -
// - (Minecraft.getTickTargetMillis: 50 ms, or the server's slower tick rate while its level runs normally), and after -
// - a pause it catches up at most 10 ticks at a time and drops the rest (Minecraft.runTick, DeltaTracker.Timer), so it -
// - never gets ahead of real time. The server receives the ticks with a delay that varies: when the connection -
// - stalled, the ticks of the stall arrive at once. The budget therefore refills with the time between the arrivals -
// - of the client's tick ends, up to the ticks of the longest burst allowed, and a tick beyond it was ended sooner -
// - than a vanilla client can. Used by the connection's tasks only, which run one at a time -
final class TickBudget {

    private static final double NANOS_PER_MILLISECOND = 1.0E6;
    // - The client's timer runs on the client's clock, which drifts apart from the server's: a client whose clock runs -
    // - fast ends its ticks a little faster than the server's real time, and over a long connection that would use up -
    // - any budget that refills at exactly the tick rate. The budget refills this much faster, far more than clocks -
    // - drift; a client ending its ticks 1% faster costs the simulation only 1% more -
    static final double REFILL_RATE = 1.01;

    private final long maximumBurstNanos;
    // - The ticks the client may still end now, and when its last tick end arrived -
    private double available;
    private long lastArrival;
    private boolean started;

    TickBudget(long maximumBurstNanos) {
        this.maximumBurstNanos = maximumBurstNanos;
    }

    // - Takes a tick that arrived at this time (System.nanoTime) while the client's ticks take this many -
    // - milliseconds; false when the budget is used up. It starts full -
    boolean take(long arrivedAt, double millisPerTick) {
        double capacity = this.maximumBurstNanos / NANOS_PER_MILLISECOND / millisPerTick;
        if (this.started) {
            long elapsed = Math.max(0L, arrivedAt - this.lastArrival);
            this.available = Math.min(capacity, this.available + elapsed * REFILL_RATE / NANOS_PER_MILLISECOND / millisPerTick);
        } else {
            this.available = capacity;
            this.started = true;
        }
        this.lastArrival = arrivedAt;
        if (this.available < 1.0) {
            return false;
        }
        this.available -= 1.0;
        return true;
    }
}
