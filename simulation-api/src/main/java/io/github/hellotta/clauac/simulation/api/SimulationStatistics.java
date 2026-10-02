package io.github.hellotta.clauac.simulation.api;

// - What the simulation of one connection has cost since it started, and how far it is behind the connection. -
// - Times are wall-clock nanoseconds -
public record SimulationStatistics(
        // - Since the simulation of the connection started -
        long elapsedNanos,
        // - The time the runtime's simulation threads spent on the connection's packets and ticks -
        long busyNanos,
        // - Client ticks simulated, and the longest time one took. A tick's time is what the simulation spent on the -
        // - connection since the previous tick: the packets in between and the tick itself -
        long ticks,
        long maxTickNanos,
        // - The median and the 99th percentile of the time of the last recentTicks ticks -
        int recentTicks,
        long recentMedianTickNanos,
        long recentPercentile99TickNanos,
        // - The connection's packets that wait for the simulation, their size, how long the oldest has waited, and the -
        // - longest any packet waited -
        int queuedPackets,
        long queuedBytes,
        long lagNanos,
        long maxLagNanos,
        // - The server's packets the client has not provably processed yet, and their size -
        int unconfirmedPackets,
        long unconfirmedBytes,
        // - Snapshots of the player's state taken for the alternatives of uncertain ticks, the time taking and -
        // - restoring them took, and the most objects one held -
        long snapshots,
        long snapshotNanos,
        int maxSnapshotObjects
) {
}
