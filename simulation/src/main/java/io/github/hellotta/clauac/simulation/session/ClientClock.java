package io.github.hellotta.clauac.simulation.session;

// - The least real time the client's timer can have reached, checked against the arrival of its tick ends. A vanilla -
// - client ends one tick per tick target of real time and never gets ahead of real time (see TickBudget). When the -
// - client answers one of the server's packets (a pong, the acceptance of a teleport, a rotation, the start of a -
// - configuration phase), it has processed that packet, so its timer has reached at least the time the server sent -
// - it; each tick it ends after that advances its timer by the tick target. A tick end cannot arrive before the -
// - client ended that tick, so a tick end that arrives earlier than the timer can have reached it was ended sooner -
// - than a vanilla client's timer allows. A connection that stalls only makes the tick ends arrive later. Used by the -
// - connection's tasks only, which run one at a time -
final class ClientClock {

    private static final double NANOS_PER_MILLISECOND = 1.0E6;
    // - After a pause a vanilla client runs up to 10 ticks at once, and the tick its leftover fraction completes may -
    // - follow right away (Minecraft.runTick, DeltaTracker.Timer.advanceGameTime), so its ticks can get 11 tick -
    // - targets ahead of the real time between them; one more tick target of margin -
    static final int SLACK_TICKS = 12;
    // - The timer advances 1% less than the tick target per tick, for a client whose clock runs faster than the -
    // - server's, as TickBudget.REFILL_RATE allows -
    private static final double TICK_SHARE = 1.0 / TickBudget.REFILL_RATE;

    // - The latest time the server sent a packet that the client provably processed before its next tick, and the -
    // - least time the client's timer can have reached with its last tick (System.nanoTime); unset before the -
    // - client's first answer -
    private boolean reachedKnown;
    private long reachedNanos;
    private boolean timerKnown;
    private long timerNanos;

    // - The client answered a packet the server sent at this time -
    void reached(long sentNanos) {
        if (!this.reachedKnown || sentNanos - this.reachedNanos > 0L) {
            this.reachedNanos = sentNanos;
            this.reachedKnown = true;
        }
    }

    // - Advances the timer by a tick whose end arrived at this time and whose target was this many milliseconds. -
    // - Returns how far the timer is ahead of that arrival in nanoseconds when that is more than slackNanos allows, -
    // - and 0 otherwise; the timer is then put back to the slack, so that only a tick further ahead fails next -
    long tick(long arrivedNanos, double millisPerTick) {
        double tickNanos = millisPerTick * NANOS_PER_MILLISECOND;
        if (this.timerKnown) {
            this.timerNanos += (long) (tickNanos * TICK_SHARE);
        }
        if (this.reachedKnown && (!this.timerKnown || this.reachedNanos - this.timerNanos > 0L)) {
            this.timerNanos = this.reachedNanos;
            this.timerKnown = true;
        }
        if (!this.timerKnown) {
            return 0L;
        }
        long slack = slackNanos(millisPerTick);
        long ahead = this.timerNanos - arrivedNanos;
        if (ahead <= slack) {
            return 0L;
        }
        this.timerNanos = arrivedNanos + slack;
        return ahead;
    }

    static long slackNanos(double millisPerTick) {
        return (long) (SLACK_TICKS * millisPerTick * NANOS_PER_MILLISECOND);
    }
}
