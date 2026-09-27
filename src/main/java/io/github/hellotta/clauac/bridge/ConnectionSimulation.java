package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.PlayerSimulation;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import io.github.hellotta.clauac.simulation.api.SimulationRuntime;
import io.github.hellotta.clauac.simulation.api.SimulationStatistics;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - The simulation of one connection. A connection is simulated from its first configuration packet on, or not at -
// - all: the simulation has to see everything from the start to know the registries and the world the client has. A -
// - connection whose configuration starts while the vanilla runtime is still starting waits: its packets are kept in -
// - order and handed to the simulation once the runtime is ready. -
// -
// - The simulation must know between which of the client's ticks the client processed each server packet. Every -
// - packet the simulation needs goes to the client inside a bundle that ends with a ping: the client handles a bundle -
// - in one go (ClientPacketListener.handleBundlePacket runs all its packets in one task on the client's main thread) -
// - and answers the ping right there, so no client tick can fall between a packet and the ping behind it. -
// -
// - Everything except the getters runs on the connection's event loop, where all of its packets are handled one -
// - after another -
final class ConnectionSimulation {

    enum State {
        // - Keeps the connection's packets until the vanilla runtime has started -
        WAITING,
        SIMULATED,
        NOT_SIMULATED
    }

    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final long MEBIBYTE = 1024L * 1024L;
    // - How much of its packets a waiting connection may keep, and how much all waiting connections together may -
    // - keep. A joining client receives a few megabytes of chunks for a usual view distance while the runtime starts; -
    // - a connection that would exceed either limit is not simulated rather than risking the server's memory -
    private static final long MAXIMUM_WAITING_BYTES = 32L * MEBIBYTE;
    private static final long MAXIMUM_TOTAL_WAITING_BYTES = 256L * MEBIBYTE;
    // - The client refuses a bundle of more than BundlerInfo.BUNDLE_SIZE_LIMIT (4096) packets. A bundle is ended -
    // - before it would hold more than this, which leaves room for the ping that ends it -
    private static final int MAXIMUM_BUNDLE_PACKETS = 4000;
    // - The ids of the connection's own pings: 2^30 ids from the lowest int up, far from the small counters around 0 -
    // - that other plugins use for their pings, whose pongs must pass through untouched (see consumeOwnPong). Each -
    // - connection starts at a random place among them -
    private static final int OWN_PING_ID_BASE = Integer.MIN_VALUE;
    private static final int OWN_PING_ID_MASK = (1 << 30) - 1;
    // - The unanswered pings remembered at most; the pongs a client sends for older ones pass through to the server -
    private static final int MAXIMUM_OWN_PINGS = 100_000;
    // - The payload of the play pong (serverbound minecraft:pong): the id as an int -
    private static final int PONG_PAYLOAD_BYTES = Integer.BYTES;

    private record WaitingPacket(ProtocolPhase phase, PacketDirection direction, int packetId, byte[] encodedPacket) {
    }

    private final User user;
    private final Path reportDirectory;
    private final Logger logger;
    // - Shared by all connections of the bridge -
    private final AtomicLong totalWaitingBytes;
    private final LocalDateTime startTime;
    private volatile State state;
    private volatile @Nullable String notSimulatedReason;
    private volatile @Nullable TickReporter reporter;
    // - Set on the event loop; read by statistics from any thread -
    private volatile @Nullable PlayerSimulation simulation;
    private @Nullable SimulationRuntime runtime;
    private final Deque<WaitingPacket> waitingPackets = new ArrayDeque<>();
    private long waitingBytes;
    // - Whether one of the connection's own bundles is open on the wire, how many packets it holds, and whether the -
    // - task that ends it is queued -
    private boolean bundleOpen;
    private int bundlePackets;
    private boolean bundleEndScheduled;
    private int pingSequence = ThreadLocalRandom.current().nextInt();
    // - The ids of the connection's own pings that the client has not answered yet, oldest first -
    private final Set<Integer> ownPingIds = new LinkedHashSet<>();

    private ConnectionSimulation(User user, Path reportDirectory, Logger logger, AtomicLong totalWaitingBytes, State state, @Nullable String notSimulatedReason) {
        this.user = user;
        this.reportDirectory = reportDirectory;
        this.logger = logger;
        this.totalWaitingBytes = totalWaitingBytes;
        this.startTime = LocalDateTime.now();
        this.state = state;
        this.notSimulatedReason = notSimulatedReason;
    }

    static ConnectionSimulation notSimulated(User user, Path reportDirectory, Logger logger, AtomicLong totalWaitingBytes, String reason) {
        return new ConnectionSimulation(user, reportDirectory, logger, totalWaitingBytes, State.NOT_SIMULATED, reason);
    }

    // - A connection that starts its configuration now: simulated right away, or waiting while the runtime starts -
    static ConnectionSimulation begin(User user, Path reportDirectory, Logger logger, AtomicLong totalWaitingBytes, @Nullable SimulationRuntime runtime) {
        ConnectionSimulation connection = new ConnectionSimulation(user, reportDirectory, logger, totalWaitingBytes, State.WAITING, null);
        if (runtime != null) {
            connection.start(runtime);
        } else {
            logger.info("Keeping the packets of {} until the vanilla runtime has started", user.getName());
        }
        return connection;
    }

    User user() {
        return this.user;
    }

    State state() {
        return this.state;
    }

    @Nullable TickReporter reporter() {
        return this.reporter;
    }

    @Nullable String notSimulatedReason() {
        return this.notSimulatedReason;
    }

    // - What the simulation has cost so far; null while the connection is not simulated -
    @Nullable SimulationStatistics statistics() {
        PlayerSimulation current = this.simulation;
        return current != null ? current.statistics() : null;
    }

    void runInEventLoop(Runnable task) {
        ChannelHelper.runInEventLoop(this.user.getChannel(), task);
    }

    // - Called for every packet of the connection, on its event loop, with the runtime once it has started or the -
    // - reason why it could not be started -
    void observe(ProtocolPacketEvent event, ProtocolPhase phase, PacketDirection direction, @Nullable SimulationRuntime currentRuntime, @Nullable String runtimeFailure) {
        boolean clientboundPlay = direction == PacketDirection.CLIENTBOUND && phase == ProtocolPhase.PLAY;
        PacketTypeCommon type = event.getPacketType();
        if (clientboundPlay && type == PacketType.Play.Server.BUNDLE && this.bundleOpen) {
            // - A delimiter of a vanilla bundle while one of the connection's own is open: the own bundle already -
            // - makes the client handle those packets together, and the delimiter would end it early -
            event.setCancelled(true);
            return;
        }
        if (this.state == State.WAITING) {
            if (currentRuntime != null) {
                this.runtimeReady(currentRuntime);
            } else if (runtimeFailure != null) {
                this.runtimeFailed(runtimeFailure);
            }
        }
        int packetId = event.getPacketId();
        boolean handedOver = switch (this.state) {
            // - Which packets matter is only known once the runtime has started -
            case WAITING -> true;
            case SIMULATED -> Objects.requireNonNull(this.runtime).isRelevant(phase, direction, packetId);
            case NOT_SIMULATED -> false;
        };
        // - A connection that is not simulated only still ends a bundle it had opened -
        boolean managesBundles = clientboundPlay && (this.state != State.NOT_SIMULATED || this.bundleOpen);
        if (!handedOver && !managesBundles) {
            return;
        }
        boolean covered = clientboundPlay && handedOver && needsPing(type);
        // - Post tasks run once the packet is final: after every listener and right before it is written, with the -
        // - whole packet (id and payload) readable, or nothing readable when it was cancelled and therefore never -
        // - sent or processed -
        event.getPostTasks().add(() -> {
            Object buffer = event.getByteBuf();
            if (!ByteBufHelper.isReadable(buffer)) {
                return;
            }
            if (managesBundles) {
                this.beforeWrite(type, covered);
            }
            if (handedOver) {
                this.handOver(phase, direction, packetId, ByteBufHelper.copyBytes(buffer));
            }
        });
    }

    // - Play packets that need a ping behind them: every one the simulation needs except the ping itself, the start -
    // - of a configuration phase (answered by its acknowledgement) and a bundle delimiter -
    private static boolean needsPing(PacketTypeCommon type) {
        return type != PacketType.Play.Server.PING
                && type != PacketType.Play.Server.BUNDLE
                && type != PacketType.Play.Server.CONFIGURATION_START
                && type != PacketType.Play.Server.DISCONNECT;
    }

    // - Keeps the connection's own bundles around the packets about to be written -
    private void beforeWrite(PacketTypeCommon type, boolean covered) {
        if (type == PacketType.Play.Server.BUNDLE) {
            // - A vanilla bundle begins while none of the connection's own is open: it becomes one of the own -
            // - bundles, ended with a ping like the others; its closing delimiter comes while it is open and is -
            // - cancelled. A connection that is not simulated leaves vanilla bundles alone -
            if (this.state != State.NOT_SIMULATED) {
                this.bundleOpen = true;
                this.bundlePackets = 0;
                this.scheduleBundleEnd();
            }
            return;
        }
        if (type == PacketType.Play.Server.CONFIGURATION_START || type == PacketType.Play.Server.DISCONNECT) {
            // - The client refuses a terminal packet inside a bundle (PacketBundlePacker), and would never handle a -
            // - disconnect held back in a bundle that the closed connection cannot end any more -
            if (this.bundleOpen) {
                this.endBundle();
            }
            return;
        }
        if (this.bundleOpen) {
            if (this.bundlePackets >= MAXIMUM_BUNDLE_PACKETS) {
                this.endBundle();
                this.beginBundle();
            }
            this.bundlePackets++;
        } else if (covered) {
            this.beginBundle();
            this.bundlePackets = 1;
        }
    }

    // - The opening delimiter goes right before the packet that needs it and is flushed together with it -
    private void beginBundle() {
        this.user.writePacketSilently(new WrapperPlayServerBundle());
        this.bundleOpen = true;
        this.bundlePackets = 0;
        this.scheduleBundleEnd();
    }

    // - The ping and the closing delimiter are sent silently: PacketEvents already treats the connection as -
    // - configuring while the start of a configuration phase is being sent (it switches before any listener runs), -
    // - so a listener would misread a ping sent then. The simulation gets the ping directly, in its place on the wire -
    private void endBundle() {
        WrapperPlayServerPing ping = new WrapperPlayServerPing(this.nextPingId());
        Object buffer = ChannelHelper.pooledByteBuf(this.user.getChannel());
        ping.setBuffer(buffer);
        ping.writeVarInt(ping.getNativePacketId());
        ping.write();
        this.handOver(ProtocolPhase.PLAY, PacketDirection.CLIENTBOUND, ping.getNativePacketId(), ByteBufHelper.copyBytes(buffer));
        this.user.sendPacketSilently(buffer);
        this.user.sendPacketSilently(new WrapperPlayServerBundle());
        this.bundleOpen = false;
        this.bundlePackets = 0;
    }

    // - Ends the open bundle once the event loop has written what it was already asked to, so that one bundle and -
    // - one ping cover everything written in the meantime -
    private void scheduleBundleEnd() {
        if (this.bundleEndScheduled) {
            return;
        }
        this.bundleEndScheduled = true;
        Object channel = this.user.getChannel();
        this.runInEventLoop(() -> {
            this.bundleEndScheduled = false;
            if (this.bundleOpen && ChannelHelper.isOpen(channel) && this.user.getEncoderState() == ConnectionState.PLAY) {
                this.endBundle();
            }
        });
    }

    // - The simulation matches every ping with its pong regardless of who sent it; the plugin remembers its own ids -
    // - to take their pongs out of the connection -
    private int nextPingId() {
        int id = OWN_PING_ID_BASE + (this.pingSequence++ & OWN_PING_ID_MASK);
        this.ownPingIds.add(id);
        if (this.ownPingIds.size() > MAXIMUM_OWN_PINGS) {
            Iterator<Integer> oldest = this.ownPingIds.iterator();
            oldest.next();
            oldest.remove();
        }
        return id;
    }

    // - Called on the event loop for every play pong of the client, with the priority that decides a packet's final -
    // - state. A pong that answers one of the connection's own pings goes to the simulation in its place and no -
    // - further: the server never sent that ping and ignores pongs anyway (ServerCommonPacketListenerImpl.handlePong), -
    // - while Paper's packet limiter, which counts every packet the server decodes (Connection.channelRead0), would -
    // - count it against the client. Every other pong, a malformed one included, goes on to the server as it came -
    void consumeOwnPong(PacketReceiveEvent event) {
        Object buffer = event.getByteBuf();
        if (ByteBufHelper.readableBytes(buffer) != PONG_PAYLOAD_BYTES) {
            return;
        }
        byte[] payload = ByteBufHelper.copyBytes(buffer);
        if (!this.ownPingIds.remove(ByteBuffer.wrap(payload).getInt())) {
            return;
        }
        event.setCancelled(true);
        int packetId = event.getPacketId();
        boolean handedOver = switch (this.state) {
            case WAITING -> true;
            case SIMULATED -> Objects.requireNonNull(this.runtime).isRelevant(ProtocolPhase.PLAY, PacketDirection.SERVERBOUND, packetId);
            case NOT_SIMULATED -> false;
        };
        if (handedOver) {
            Object encoded = ChannelHelper.pooledByteBuf(this.user.getChannel());
            try {
                ByteBufHelper.writeVarInt(encoded, packetId);
                ByteBufHelper.writeBytes(encoded, payload);
                this.handOver(ProtocolPhase.PLAY, PacketDirection.SERVERBOUND, packetId, ByteBufHelper.copyBytes(encoded));
            } finally {
                ByteBufHelper.release(encoded);
            }
        }
    }

    private void handOver(ProtocolPhase phase, PacketDirection direction, int packetId, byte[] encodedPacket) {
        switch (this.state) {
            case SIMULATED -> Objects.requireNonNull(this.simulation).handlePacket(phase, direction, encodedPacket);
            case WAITING -> this.keepWaiting(new WaitingPacket(phase, direction, packetId, encodedPacket));
            case NOT_SIMULATED -> {
            }
        }
    }

    private void keepWaiting(WaitingPacket packet) {
        long size = packet.encodedPacket().length;
        if (this.waitingBytes + size > MAXIMUM_WAITING_BYTES) {
            this.stopWaiting("more than " + MAXIMUM_WAITING_BYTES / MEBIBYTE + " MiB of its packets arrived while the vanilla runtime was starting");
            return;
        }
        if (this.totalWaitingBytes.addAndGet(size) > MAXIMUM_TOTAL_WAITING_BYTES) {
            this.totalWaitingBytes.addAndGet(-size);
            this.stopWaiting("the connections waiting for the vanilla runtime to start already held " + MAXIMUM_TOTAL_WAITING_BYTES / MEBIBYTE + " MiB");
            return;
        }
        this.waitingBytes += size;
        this.waitingPackets.add(packet);
    }

    // - The runtime has started: a waiting connection starts its simulation with everything it kept, in order -
    void runtimeReady(SimulationRuntime currentRuntime) {
        if (this.state != State.WAITING) {
            return;
        }
        this.totalWaitingBytes.addAndGet(-this.waitingBytes);
        this.waitingBytes = 0L;
        this.start(currentRuntime);
        PlayerSimulation started = this.simulation;
        if (started != null) {
            for (WaitingPacket packet : this.waitingPackets) {
                if (currentRuntime.isRelevant(packet.phase(), packet.direction(), packet.packetId())) {
                    started.handlePacket(packet.phase(), packet.direction(), packet.encodedPacket());
                }
            }
        }
        this.waitingPackets.clear();
    }

    void runtimeFailed(String reason) {
        if (this.state == State.WAITING) {
            this.stopWaiting(reason);
        }
    }

    private void stopWaiting(String reason) {
        this.totalWaitingBytes.addAndGet(-this.waitingBytes);
        this.waitingBytes = 0L;
        this.waitingPackets.clear();
        this.notSimulatedReason = reason;
        this.state = State.NOT_SIMULATED;
        this.logger.warn("Not simulating {}: {}", this.user.getName(), reason);
    }

    private void start(SimulationRuntime currentRuntime) {
        String name = this.user.getName();
        Path csvFile = this.reportDirectory.resolve(this.startTime.format(REPORT_TIME) + "-" + name + ".csv");
        TickReporter newReporter;
        try {
            newReporter = new TickReporter(this.user, name, csvFile, this.logger);
        } catch (IOException exception) {
            this.logger.error("Could not create {}", csvFile, exception);
            this.notSimulatedReason = "its report file could not be created";
            this.state = State.NOT_SIMULATED;
            return;
        }
        this.runtime = currentRuntime;
        this.simulation = currentRuntime.createPlayer(this.user.getUUID(), name, newReporter);
        this.reporter = newReporter;
        this.state = State.SIMULATED;
        this.logger.info("Simulating {} ({}), recording to {}", name, this.user.getUUID(), csvFile);
    }

    void close() {
        this.totalWaitingBytes.addAndGet(-this.waitingBytes);
        this.waitingBytes = 0L;
        this.waitingPackets.clear();
        PlayerSimulation closingSimulation = this.simulation;
        if (closingSimulation != null) {
            closingSimulation.close();
        }
        TickReporter closing = this.reporter;
        if (closing != null) {
            closing.close();
        }
    }
}
