package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.protocol.player.User;
import io.github.hellotta.clauac.simulation.api.PlayerSimulation;
import org.jspecify.annotations.Nullable;

// - The simulation of one connection, or the reason why the connection is not simulated -
final class ConnectionSimulation {

    private final User user;
    private final @Nullable PlayerSimulation simulation;
    private final @Nullable TickReporter reporter;
    private final @Nullable String notSimulatedReason;
    // - Only used on the connection's event loop -
    private int nextPingId = -1;

    private ConnectionSimulation(User user, @Nullable PlayerSimulation simulation, @Nullable TickReporter reporter, @Nullable String notSimulatedReason) {
        this.user = user;
        this.simulation = simulation;
        this.reporter = reporter;
        this.notSimulatedReason = notSimulatedReason;
    }

    static ConnectionSimulation simulated(User user, PlayerSimulation simulation, TickReporter reporter) {
        return new ConnectionSimulation(user, simulation, reporter, null);
    }

    static ConnectionSimulation notSimulated(User user, String reason) {
        return new ConnectionSimulation(user, null, null, reason);
    }

    User user() {
        return this.user;
    }

    @Nullable PlayerSimulation simulation() {
        return this.simulation;
    }

    @Nullable TickReporter reporter() {
        return this.reporter;
    }

    @Nullable String notSimulatedReason() {
        return this.notSimulatedReason;
    }

    // - Ids count down from -1 so that they never collide with the positive ids other plugins tend to use; the -
    // - simulation matches every ping with its pong regardless of who sent it -
    int nextPingId() {
        return this.nextPingId--;
    }

    void close() {
        if (this.simulation != null) {
            this.simulation.close();
        }
        if (this.reporter != null) {
            this.reporter.close();
        }
    }
}
