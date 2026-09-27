package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import io.github.hellotta.clauac.simulation.api.SimulationRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - Hands every relevant packet of a connection to its simulation, exactly as it travels on the wire; see -
// - ConnectionSimulation for how the pings behind the server's packets tell the simulation how far the client has -
// - processed them when it simulates the client's next tick -
public final class SimulationBridge implements PacketListener {

    // - How far from the player's bounding box another entity counts as able to push or carry it -
    private static final double NEARBY_ENTITY_DISTANCE = 1.0;

    private final Logger logger;
    private final Path reportDirectory;
    private final Map<Object, ConnectionSimulation> connections = new ConcurrentHashMap<>();
    // - What the connections that wait for the runtime to start keep, together -
    private final AtomicLong totalWaitingBytes = new AtomicLong();
    private volatile @Nullable SimulationRuntime runtime;
    private volatile @Nullable String runtimeFailure;

    public SimulationBridge(Logger logger, Path reportDirectory) {
        this.logger = logger;
        this.reportDirectory = reportDirectory;
    }

    // - Waiting connections start on their own event loops; one created at the same moment sees the runtime with its -
    // - next packet at the latest -
    public void setRuntime(SimulationRuntime runtime) {
        this.runtime = runtime;
        for (ConnectionSimulation connection : this.connections.values()) {
            connection.runInEventLoop(() -> connection.runtimeReady(runtime));
        }
    }

    public void setRuntimeFailure(String reason) {
        this.runtimeFailure = reason;
        for (ConnectionSimulation connection : this.connections.values()) {
            connection.runInEventLoop(() -> connection.runtimeFailed(reason));
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        this.observe(event, PacketDirection.CLIENTBOUND);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        this.observe(event, PacketDirection.SERVERBOUND);
    }

    private void observe(ProtocolPacketEvent event, PacketDirection direction) {
        ProtocolPhase phase = phaseOf(event.getConnectionState());
        if (phase == null) {
            return;
        }
        this.connectionFor(event.getUser(), phase).observe(event, phase, direction, this.runtime, this.runtimeFailure);
    }

    private static @Nullable ProtocolPhase phaseOf(ConnectionState state) {
        return switch (state) {
            case CONFIGURATION -> ProtocolPhase.CONFIGURATION;
            case PLAY -> ProtocolPhase.PLAY;
            case HANDSHAKING, STATUS, LOGIN -> null;
        };
    }

    // - Called on the connection's event loop, where all of its packets are handled one after another -
    private ConnectionSimulation connectionFor(User user, ProtocolPhase phase) {
        Object channel = user.getChannel();
        ConnectionSimulation existing = this.connections.get(channel);
        if (existing != null) {
            return existing;
        }
        ConnectionSimulation created = this.startConnection(user, phase);
        this.connections.put(channel, created);
        if (created.state() == ConnectionSimulation.State.NOT_SIMULATED) {
            this.logger.warn("Not simulating {}: {}", user.getName(), created.notSimulatedReason());
        }
        // - The runtime may have started between creating the connection and making it visible to setRuntime -
        SimulationRuntime currentRuntime = this.runtime;
        if (currentRuntime != null) {
            created.runtimeReady(currentRuntime);
        }
        return created;
    }

    // - A connection is simulated from its first configuration packet on, or not at all; see ConnectionSimulation -
    private ConnectionSimulation startConnection(User user, ProtocolPhase phase) {
        if (phase != ProtocolPhase.CONFIGURATION) {
            return ConnectionSimulation.notSimulated(user, this.reportDirectory, this.logger, this.totalWaitingBytes,
                    "the connection was already playing when ClauAC started watching it");
        }
        String failure = this.runtimeFailure;
        if (failure != null) {
            return ConnectionSimulation.notSimulated(user, this.reportDirectory, this.logger, this.totalWaitingBytes, failure);
        }
        return ConnectionSimulation.begin(user, this.reportDirectory, this.logger, this.totalWaitingBytes, this.runtime);
    }

    @Override
    public void onUserDisconnect(UserDisconnectEvent event) {
        ConnectionSimulation connection = this.connections.remove(event.getUser().getChannel());
        if (connection != null) {
            this.close(connection);
        }
    }

    private void close(ConnectionSimulation connection) {
        connection.close();
        TickReporter reporter = connection.reporter();
        if (reporter != null) {
            this.logger.info("Simulation summary for {}", reporter.summary());
        }
    }

    // - Called on the server thread at the end of every server tick -
    public void onServerTickEnd(Iterable<? extends Player> onlinePlayers) {
        for (Player player : onlinePlayers) {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null) {
                continue;
            }
            ConnectionSimulation connection = this.connections.get(user.getChannel());
            TickReporter reporter = connection != null ? connection.reporter() : null;
            if (reporter != null) {
                reporter.setEntityNearby(!player.getNearbyEntities(NEARBY_ENTITY_DISTANCE, NEARBY_ENTITY_DISTANCE, NEARBY_ENTITY_DISTANCE).isEmpty());
                if (reporter.takeInventoryResyncRequest()) {
                    this.logger.info("Resending the inventory of {}: the simulated items differ from the client's", player.getName());
                    player.updateInventory();
                }
            }
        }
    }

    public @Nullable Boolean toggleActionBar(Player player) {
        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        ConnectionSimulation connection = user != null ? this.connections.get(user.getChannel()) : null;
        TickReporter reporter = connection != null ? connection.reporter() : null;
        if (reporter == null) {
            return null;
        }
        boolean enabled = !reporter.isActionBarEnabled();
        reporter.setActionBarEnabled(enabled);
        return enabled;
    }

    public List<String> status() {
        List<String> lines = new ArrayList<>();
        for (ConnectionSimulation connection : this.connections.values()) {
            TickReporter reporter = connection.reporter();
            String name = connection.user().getName();
            lines.add(switch (connection.state()) {
                case SIMULATED -> Objects.requireNonNull(reporter, "a simulated connection has a reporter").summary();
                case WAITING -> name + ": waiting for the vanilla runtime to start";
                case NOT_SIMULATED -> name + ": not simulated, " + connection.notSimulatedReason();
            });
        }
        return lines;
    }

    public void closeAll() {
        for (Object channel : List.copyOf(this.connections.keySet())) {
            ConnectionSimulation connection = this.connections.remove(channel);
            if (connection != null) {
                this.close(connection);
            }
        }
    }
}
