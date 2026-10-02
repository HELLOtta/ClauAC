package io.github.hellotta.clauac.simulation.session;

// - The least real time the client's timer can have reached, checked against the arrival of its tick ends. A vanilla -
// - client ends one tick per tick target of real time and never gets ahead of real time (see TickBudget). When the -
// - client answers one of the server's packets (a pong, the acceptance of a teleport, a rotation, the start of a -
// - configuration phase), it has processed that packet, so its timer has reached at least the time the server sent -
// - it; each tick it ends after that advances its timer by the tick target. A tick end cannot arrive before the -
// - client ended that tick, so a tick end that arrives earlier than the timer can have reached it, by more than the -
// - ticks a vanilla client may still owe for the time before its answer (see SLACK_TICKS), was ended sooner than a -
// - vanilla client's timer allows. A connection that stalls only makes the tick ends arrive later. Used by the -
// - connection's tasks only, which run one at a time -
final class ClientClock {

    private static final double NANOS_PER_MILLISECOND = 1.0E6;
    // - How many tick targets a vanilla client's ticks can get ahead of the real time since the server sent a packet -
    // - the client answered. A frame counts the ticks it owes when it starts, handles the server's packets and then -
    // - runs those ticks, at most 10 at once (Minecraft.runTick, DeltaTracker.Timer.advanceGameTime): the ticks right -
    // - after an answer can be owed for up to 10 tick targets before the frame started. The frame handles every packet -
    // - that arrives while it handles packets (PacketProcessor.processQueuedPackets runs until the queue is empty), -
    // - which took up to 650 ms in test joins, whose chunks arrived meanwhile; the next frame owes ticks for that time, -
    // - up to 10 more. The tick the leftover fraction completes may follow right away, and one more tick target of -
    // - margin -
    static final int SLACK_TICKS = 22;
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
