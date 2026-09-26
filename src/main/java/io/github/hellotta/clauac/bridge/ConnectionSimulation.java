package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
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
    private boolean pingScheduled;

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

    // - Called on the event loop right after a relevant play packet was handed on. The ping follows everything the -
    // - event loop was already asked to write, so it reaches the client right behind the packet; the client answers -
    // - it once it has processed the packet, which tells the simulation between which of the client's ticks that -
    // - happened. One ping covers every packet written before it -
    void schedulePing() {
        if (this.pingScheduled) {
            return;
        }
        this.pingScheduled = true;
        Object channel = this.user.getChannel();
        ChannelHelper.runInEventLoop(channel, () -> {
            this.pingScheduled = false;
            // - Only valid in the play phase, which may have ended in the meantime -
            if (ChannelHelper.isOpen(channel) && this.user.getEncoderState() == ConnectionState.PLAY) {
                this.user.sendPacket(new WrapperPlayServerPing(this.nextPingId()));
            }
        });
    }

    // - Ids count down from -1 so that they never collide with the positive ids other plugins tend to use; the -
    // - simulation matches every ping with its pong regardless of who sent it -
    private int nextPingId() {
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
