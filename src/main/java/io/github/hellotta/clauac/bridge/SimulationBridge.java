package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.event.UserDisconnectEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.PlayerSimulation;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import io.github.hellotta.clauac.simulation.api.SimulationRuntime;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - Hands every relevant packet of a connection to its simulation, exactly as it travels on the wire, and follows -
// - every relevant play packet with a ping. The client answers pings in order, which tells the simulation how far -
// - the client has processed the server's packets when it simulates the client's next tick -
public final class SimulationBridge implements PacketListener {

    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    // - How far from the player's bounding box another entity counts as able to push or carry it -
    private static final double NEARBY_ENTITY_DISTANCE = 1.0;

    private final Logger logger;
    private final Path reportDirectory;
    private final Map<Object, ConnectionSimulation> connections = new ConcurrentHashMap<>();
    private volatile @Nullable SimulationRuntime runtime;

    public SimulationBridge(Logger logger, Path reportDirectory) {
        this.logger = logger;
        this.reportDirectory = reportDirectory;
    }

    // - Connections that start their configuration afterwards are simulated -
    public void setRuntime(SimulationRuntime runtime) {
        this.runtime = runtime;
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
        ConnectionSimulation connection = this.connectionFor(event.getUser(), phase);
        PlayerSimulation simulation = connection.simulation();
        SimulationRuntime currentRuntime = this.runtime;
        if (simulation == null || currentRuntime == null || !currentRuntime.isRelevant(phase, direction, event.getPacketId())) {
            return;
        }
        // - The simulation's own pings are relevant too, but must not be followed by another one -
        boolean needsPing = direction == PacketDirection.CLIENTBOUND && phase == ProtocolPhase.PLAY
                && event.getPacketType() != PacketType.Play.Server.PING;
        // - Post tasks run once the packet is final: after every listener, with the whole packet (id and payload) -
        // - readable, or nothing readable when it was cancelled and therefore never sent or processed -
        event.getPostTasks().add(() -> {
            Object buffer = event.getByteBuf();
            if (ByteBufHelper.isReadable(buffer)) {
                simulation.handlePacket(phase, direction, ByteBufHelper.copyBytes(buffer));
                if (needsPing) {
                    connection.schedulePing();
                }
            }
        });
    }

    private static @Nullable ProtocolPhase phaseOf(ConnectionState state) {
        return switch (state) {
            case CONFIGURATION -> ProtocolPhase.CONFIGURATION;
            case PLAY -> ProtocolPhase.PLAY;
            case HANDSHAKING, STATUS, LOGIN -> null;
        };
    }

    // - Called on the connection's event loop, where all of its packets are handled one after another. A connection -
    // - is simulated from its first configuration packet on, or not at all: the simulation has to see everything -
    // - from the start to know the registries and the world the client has -
    private ConnectionSimulation connectionFor(User user, ProtocolPhase phase) {
        Object channel = user.getChannel();
        ConnectionSimulation existing = this.connections.get(channel);
        if (existing != null) {
            return existing;
        }
        ConnectionSimulation created = this.startConnection(user, phase);
        this.connections.put(channel, created);
        if (created.notSimulatedReason() != null) {
            this.logger.warn("Not simulating {}: {}", user.getName(), created.notSimulatedReason());
        }
        return created;
    }

    private ConnectionSimulation startConnection(User user, ProtocolPhase phase) {
        SimulationRuntime currentRuntime = this.runtime;
        if (phase != ProtocolPhase.CONFIGURATION) {
            return ConnectionSimulation.notSimulated(user, "the connection was already playing when ClauAC started watching it");
        }
        if (currentRuntime == null) {
            return ConnectionSimulation.notSimulated(user, "the vanilla runtime was still starting when the connection began");
        }
        UUID profileId = user.getUUID();
        String name = user.getName();
        Path csvFile = this.reportDirectory.resolve(LocalDateTime.now().format(REPORT_TIME) + "-" + name + ".csv");
        TickReporter reporter;
        try {
            reporter = new TickReporter(user, name, csvFile, this.logger);
        } catch (IOException exception) {
            this.logger.error("Could not create {}", csvFile, exception);
            return ConnectionSimulation.notSimulated(user, "its report file could not be created");
        }
        PlayerSimulation simulation = currentRuntime.createPlayer(profileId, name, reporter);
        this.logger.info("Simulating {} ({}), recording to {}", name, profileId, csvFile);
        return ConnectionSimulation.simulated(user, simulation, reporter);
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
            lines.add(reporter != null ? reporter.summary() : connection.user().getName() + ": not simulated, " + connection.notSimulatedReason());
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
