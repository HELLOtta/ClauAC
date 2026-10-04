package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.ProtocolPacketEvent;
import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAcknowledgeBlockChanges;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerVehicleMove;
import io.github.hellotta.clauac.response.Responses;
import io.github.hellotta.clauac.response.SetbackType;
import io.github.hellotta.clauac.response.TickResponse;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.Flag;
import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.PlayerSimulation;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import io.github.hellotta.clauac.simulation.api.SimulationRuntime;
import io.github.hellotta.clauac.simulation.api.SimulationStatistics;
import io.github.hellotta.clauac.simulation.api.TickEnd;
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
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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
// - The client's movement and actions reach the server only once the simulation has judged their tick (see -
// - TickHold). An action that fails a check never reaches it, and the client takes back what it predicted of it. A -
// - tick that is to be set back loses its movement, and so does every tick after it until the client has taken a -
// - correction: a teleport, or, when it steers a vehicle, a move of the vehicle, either to where the simulation moved -
// - it in the failed tick, which the server then gets as that tick's movement, or to where the server has it (see -
// - setbacks.type and Setbacks). The correction goes out in one of the connection's own bundles, so that the pong to -
// - the ping behind it shows when the client has taken it. -
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

    // - A setback: none under way, asked of the server thread, or its correction on the way to the client -
    private enum SetbackPhase {
        NONE,
        REQUESTED,
        CORRECTED
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
    // - The ids of the connection's own pings and teleports: 2^30 ids from the lowest int up, far from the small -
    // - counters around 0 that the server uses for its teleports and other plugins for their pings, whose answers -
    // - must pass through untouched (see consumeOwnPong). Each connection starts at a random place among them -
    private static final int OWN_ID_BASE = Integer.MIN_VALUE;
    private static final int OWN_ID_MASK = (1 << 30) - 1;
    // - The unanswered pings remembered at most; the pongs a client sends for older ones pass through to the server -
    private static final int MAXIMUM_OWN_PINGS = 100_000;
    // - The unanswered teleports remembered at most; the connection has at most one correction on the way at a time -
    private static final int MAXIMUM_OWN_TELEPORTS = 64;
    // - The payload of the play pong (serverbound minecraft:pong): the id as an int -
    private static final int PONG_PAYLOAD_BYTES = Integer.BYTES;
    // - The payload of the teleport answer after its id (serverbound minecraft:accept_teleportation): the position -
    // - as three doubles and the rotation as two floats -
    private static final int TELEPORT_ANSWER_VALUE_BYTES = 3 * Double.BYTES + 2 * Float.BYTES;
    // - A VarInt takes at most five bytes, each carrying seven bits and a continuation bit -
    private static final int MAXIMUM_VARINT_BYTES = 5;
    private static final int VARINT_VALUE_BITS = 7;
    private static final int VARINT_VALUE_MASK = 0x7F;
    private static final int VARINT_CONTINUE_BIT = 0x80;
    private static final double NANOS_PER_MILLISECOND = 1.0E6;

    // - handedInNanos is when the packet was sent or received, which the simulation measures the client's ticks by -
    private record WaitingPacket(ProtocolPhase phase, PacketDirection direction, int packetId, byte[] encodedPacket, long handedInNanos) {
    }

    // - A tick that is to be set back, with what its setback needs -
    private record FailedTick(ClientTickReport report, TickEnd end, boolean late) {
    }

    private final User user;
    private final Path reportDirectory;
    private final Logger logger;
    private final Responses responses;
    private final Setbacks setbacks;
    // - Shared by all connections of the bridge -
    private final AtomicLong totalWaitingBytes;
    private final LocalDateTime startTime;
    private final TickHold hold;
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
    private int teleportSequence = ThreadLocalRandom.current().nextInt();
    // - The ids of the connection's own teleports that the client has not answered yet, oldest first -
    private final Set<Integer> ownTeleportIds = new LinkedHashSet<>();
    private SetbackPhase setbackPhase = SetbackPhase.NONE;
    // - Counts the setbacks, so that an answer about an earlier one is recognized -
    private int setbackGeneration;
    // - The correction went into the open bundle, whose ping is still to be sent -
    private boolean correctionInOpenBundle;
    // - The ping behind the correction, whose pong ends the setback -
    private boolean correctionPingAwaited;
    private int correctionPingId;
    // - Where the pong to that ping came among the serverbound packets (TickEnd.serverboundPackets), once it came: -
    // - the client had taken the correction before every tick that ends after it -
    private long correctionAnsweredAt = -1L;
    // - The latest tick that failed after the client had taken the correction; its setback follows once the current -
    // - one is over, since the simulation continued from what that tick reported -
    private @Nullable FailedTick failedAfterCorrection;
    // - Where the server's latest teleport of the player went out among the serverbound packets -
    // - (TickEnd.serverboundPackets): the client played every tick that ended before it without that teleport, which -
    // - puts it somewhere anyway -
    private long serverTeleportAt = -1L;
    // - Where the server's latest move of the vehicle the player steers went out among the serverbound packets, as it -
    // - corrects a vehicle that moved wrongly (ServerGamePacketListenerImpl.handleMoveVehicle) -
    private long serverVehicleMoveAt = -1L;
    // - Where the end packet of the tick the setback under way is for came among the serverbound packets: a teleport -
    // - or vehicle move of the server after it has put the player where the server has it, which a correction to -
    // - where the simulation moved it would undo -
    private long setbackTickEnd = -1L;
    // - Written on the event loop only; volatile for /clauac status -
    private volatile long setbacksRequested;
    private volatile long positionCorrections;
    private volatile long predictedPositionCorrections;
    private volatile long vehicleCorrections;
    private volatile long predictedVehicleCorrections;
    private volatile long serverTeleports;
    private volatile long setbacksSkipped;
    private volatile long predictionsAcknowledged;

    private ConnectionSimulation(
            User user, Path reportDirectory, Logger logger, Responses responses, Setbacks setbacks, AtomicLong totalWaitingBytes, State state,
            @Nullable String notSimulatedReason
    ) {
        this.user = user;
        this.reportDirectory = reportDirectory;
        this.logger = logger;
        this.responses = responses;
        this.setbacks = setbacks;
        this.totalWaitingBytes = totalWaitingBytes;
        this.startTime = LocalDateTime.now();
        this.state = state;
        this.notSimulatedReason = notSimulatedReason;
        this.hold = new TickHold(user);
    }

    static ConnectionSimulation notSimulated(
            User user, Path reportDirectory, Logger logger, Responses responses, Setbacks setbacks, AtomicLong totalWaitingBytes, String reason
    ) {
        return new ConnectionSimulation(user, reportDirectory, logger, responses, setbacks, totalWaitingBytes, State.NOT_SIMULATED, reason);
    }

    // - A connection that starts its configuration now: simulated right away, or waiting while the runtime starts -
    static ConnectionSimulation begin(
            User user, Path reportDirectory, Logger logger, Responses responses, Setbacks setbacks, AtomicLong totalWaitingBytes,
            @Nullable SimulationRuntime runtime
    ) {
        ConnectionSimulation connection = new ConnectionSimulation(user, reportDirectory, logger, responses, setbacks, totalWaitingBytes, State.WAITING, null);
        if (runtime != null) {
            connection.start(runtime);
            // - Holding needs the simulation to count the same serverbound packets from the start; the waiting -
            // - packets of a connection that started before the runtime are only handed over in part (see -
            // - runtimeReady), so such a connection is never held -
            if (connection.state == State.SIMULATED) {
                connection.hold.enable();
            }
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
        boolean held = direction == PacketDirection.SERVERBOUND && phase == ProtocolPhase.PLAY && this.hold.enabled();
        if (!handedOver && !managesBundles && !held) {
            return;
        }
        boolean covered = clientboundPlay && handedOver && needsPing(type);
        // - Post tasks run once the packet is final: after every listener and right before it is written or goes on -
        // - to the server, with the whole packet (id and payload) readable, or nothing readable when it was cancelled -
        // - and therefore never sent or processed -
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
            if (clientboundPlay && type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
                // - The connection's own corrections are sent silently and never come here -
                this.serverTeleportAt = this.hold.handedOver();
            }
            if (clientboundPlay && type == PacketType.Play.Server.VEHICLE_MOVE) {
                this.serverVehicleMoveAt = this.hold.handedOver();
            }
            if (held) {
                this.hold.onServerbound(type, buffer, System.nanoTime(), this.responses.settings().maximumHoldNanos());
                if (type == PacketType.Play.Client.CONFIGURATION_ACK) {
                    // - The client leaves the play phase; the next one starts where the server puts the player -
                    this.endSetback(this.setbackGeneration);
                }
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
        if (type == PacketType.Play.Server.CONFIGURATION_START || type == PacketType.Play.Server.DISCONNECT
                || type == PacketType.Play.Server.KEEP_ALIVE) {
            // - The client refuses a terminal packet inside a bundle (PacketBundlePacker), and would never handle a -
            // - disconnect held back in a bundle that the closed connection cannot end any more. It answers a -
            // - keep-alive right away on its network thread (ClientCommonPacketListenerImpl.handleKeepAlive), but one -
            // - inside a bundle only once its main thread handles the bundle: when the connection stalled and the -
            // - keep-alives of those seconds reach the client together, a later one outside of a bundle is answered -
            // - first, and Paper disconnects a client that answers its keep-alives out of order -
            // - (ServerCommonPacketListenerImpl.handleKeepAlive) -
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
        int pingId = this.nextPingId();
        this.writeOwnPacket(new WrapperPlayServerPing(pingId));
        this.user.sendPacketSilently(new WrapperPlayServerBundle());
        this.bundleOpen = false;
        this.bundlePackets = 0;
        if (this.correctionInOpenBundle) {
            this.correctionInOpenBundle = false;
            this.correctionPingAwaited = true;
            this.correctionPingId = pingId;
        }
    }

    // - Sends one of the connection's own packets silently and hands it to the simulation in its place on the wire -
    private void writeOwnPacket(PacketWrapper<?> packet) {
        Object buffer = ChannelHelper.pooledByteBuf(this.user.getChannel());
        writePacket(packet, buffer);
        this.handOver(ProtocolPhase.PLAY, PacketDirection.CLIENTBOUND, packet.getNativePacketId(), ByteBufHelper.copyBytes(buffer));
        this.user.sendPacketSilently(buffer);
    }

    // - Writes the packet's id and payload into the buffer, as they travel inside a frame, in the server's protocol -
    private static void writePacket(PacketWrapper<?> packet, Object buffer) {
        packet.setBuffer(buffer);
        packet.writeVarInt(packet.getNativePacketId());
        packet.write();
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
        int id = OWN_ID_BASE + (this.pingSequence++ & OWN_ID_MASK);
        remember(this.ownPingIds, id, MAXIMUM_OWN_PINGS);
        return id;
    }

    private int nextTeleportId() {
        int id = OWN_ID_BASE + (this.teleportSequence++ & OWN_ID_MASK);
        remember(this.ownTeleportIds, id, MAXIMUM_OWN_TELEPORTS);
        return id;
    }

    private static void remember(Set<Integer> ids, int id, int maximum) {
        ids.add(id);
        if (ids.size() > maximum) {
            Iterator<Integer> oldest = ids.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    // - Called on the event loop for every play pong of the client, with the priority that decides a packet's final -
    // - state. A pong that answers one of the connection's own pings goes to the simulation in its place and no -
    // - further: the server never sent that ping and ignores pongs anyway (ServerCommonPacketListenerImpl.handlePong), -
    // - while Paper's packet limiter, which counts every packet the server decodes (Connection.channelRead0), would -
    // - count it against the client. Every other pong, a malformed one included, goes on to the server as it came. -
    // - The pong to the ping behind a correction ends the setback once the packets before it went on -
    void consumeOwnPong(PacketReceiveEvent event) {
        Object buffer = event.getByteBuf();
        if (ByteBufHelper.readableBytes(buffer) != PONG_PAYLOAD_BYTES) {
            return;
        }
        byte[] payload = ByteBufHelper.copyBytes(buffer);
        int id = ByteBuffer.wrap(payload).getInt();
        if (!this.ownPingIds.remove(id)) {
            return;
        }
        this.consumeOwnAnswer(event, payload);
        if (this.correctionPingAwaited && id == this.correctionPingId) {
            this.correctionPingAwaited = false;
            this.correctionAnsweredAt = this.hold.handedOver();
            int generation = this.setbackGeneration;
            this.hold.mark(() -> this.finishSetback(generation), System.nanoTime());
        }
    }

    // - Called on the event loop for every teleport answer of the client, like consumeOwnPong: the answer to one of -
    // - the connection's own teleports goes to the simulation and no further, since the server never sent that -
    // - teleport. Every other answer goes on to the server as it came -
    void consumeOwnTeleportAnswer(PacketReceiveEvent event) {
        Object buffer = event.getByteBuf();
        byte[] payload = ByteBufHelper.copyBytes(buffer);
        OptionalInt id = readVarInt(payload);
        if (id.isEmpty() || payload.length != varIntSize(id.getAsInt()) + TELEPORT_ANSWER_VALUE_BYTES || !this.ownTeleportIds.remove(id.getAsInt())) {
            return;
        }
        this.consumeOwnAnswer(event, payload);
    }

    // - Takes an answer to one of the connection's own packets out of the connection and hands it to the simulation -
    private void consumeOwnAnswer(PacketReceiveEvent event, byte[] payload) {
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

    // - The VarInt at the start of the bytes; empty when they do not start with a complete one -
    private static OptionalInt readVarInt(byte[] bytes) {
        int value = 0;
        for (int index = 0; index < MAXIMUM_VARINT_BYTES && index < bytes.length; index++) {
            int current = bytes[index];
            value |= (current & VARINT_VALUE_MASK) << (VARINT_VALUE_BITS * index);
            if ((current & VARINT_CONTINUE_BIT) == 0) {
                return OptionalInt.of(value);
            }
        }
        return OptionalInt.empty();
    }

    private static int varIntSize(int value) {
        int size = 1;
        int rest = value >>> VARINT_VALUE_BITS;
        while (rest != 0) {
            size++;
            rest >>>= VARINT_VALUE_BITS;
        }
        return size;
    }

    private void handOver(ProtocolPhase phase, PacketDirection direction, int packetId, byte[] encodedPacket) {
        switch (this.state) {
            case SIMULATED -> {
                if (direction == PacketDirection.SERVERBOUND) {
                    this.hold.countHandedOver();
                }
                Objects.requireNonNull(this.simulation).handlePacket(phase, direction, encodedPacket, System.nanoTime());
            }
            case WAITING -> this.keepWaiting(new WaitingPacket(phase, direction, packetId, encodedPacket, System.nanoTime()));
            case NOT_SIMULATED -> {
            }
        }
    }

    // - A tick's verdict, from the simulation thread, in the order of the client's ticks, with what happens to the -
    // - tick's packets -
    void onVerdict(ClientTickReport report, TickEnd end, TickResponse response) {
        this.runInEventLoop(() -> this.judge(report, end, response));
    }

    // - The tick's packets go on to the server as far as the verdict allows, without their movement when the tick is -
    // - set back and without the actions that failed a check. A tick that fails while a setback is under way needs -
    // - no setback of its own when the client had not taken the correction yet before the tick: the correction on -
    // - its way puts the client back anyway. After the correction, the simulation went on from where the correction -
    // - put the player, so such a tick needs its own setback, which follows the current one. Neither needs a tick -
    // - that ended before a teleport of the server went out, such as that of a setback whose tick's movement had -
    // - reached the server: that teleport puts the client somewhere anyway, while the simulation began the tick where -
    // - the tick before had left the player. Actions that went on unjudged already reached the server, which answers -
    // - them itself -
    private void judge(ClientTickReport report, TickEnd end, TickResponse response) {
        boolean late = this.hold.wentOnUnjudged();
        if (!response.refusedActions().isEmpty()) {
            this.refuse(response);
        }
        if (response.setBack()) {
            this.hold.dropMovementThrough(end.serverboundPackets());
            if (end.serverboundPackets() <= this.serverTeleportAt) {
                this.logger.info("Not setting {} back after client tick {} ({}): the tick ended before a teleport of the server went out",
                        this.user.getName(), report.clientTick(), describeChecks(report));
            } else if (this.setbackPhase == SetbackPhase.NONE) {
                this.startSetback(report, end, late);
            } else if (this.correctionAnsweredAt >= 0L && end.serverboundPackets() > this.correctionAnsweredAt) {
                this.failedAfterCorrection = new FailedTick(report, end, late);
            }
        }
        this.hold.judged(end.serverboundPackets(), System.nanoTime());
    }

    // - Keeps the tick's failed actions from the server as far as they are still held; those that went on unjudged -
    // - reached it, and it answers them itself. The client predicted what the kept ones do to blocks on its own and -
    // - keeps that until the server acknowledges the prediction (MultiPlayerGameMode's block prediction sequence), -
    // - which the server never does for an action it never received: the connection acknowledges the latest of their -
    // - predictions in the server's stead, in one of its own bundles, and the client takes back what it predicted -
    // - (ClientPacketListener.handleBlockChangedAck). The client uses up or changes the item of an interaction or item -
    // - use on its own as well, so the server sends it its inventory once one of those is kept from it -
    private void refuse(TickResponse response) {
        TickHold.Refusal refusal = this.hold.refuse(response.refusedActions().keySet());
        int acknowledgedSequence = Flag.NO_PREDICTION;
        for (long packet : refusal.packets()) {
            acknowledgedSequence = Math.max(acknowledgedSequence, response.refusedActions().get(packet));
        }
        if (acknowledgedSequence != Flag.NO_PREDICTION && this.user.getEncoderState() == ConnectionState.PLAY) {
            this.writeCorrection(PacketType.Play.Server.ACKNOWLEDGE_BLOCK_CHANGES, new WrapperPlayServerAcknowledgeBlockChanges(acknowledgedSequence));
            this.predictionsAcknowledged++;
        }
        TickReporter currentReporter = this.reporter;
        if (refusal.itemRefused() && currentReporter != null) {
            currentReporter.onInventoryResyncNeeded();
        }
    }

    // - The client's movement stops reaching the server now, and the server thread decides how to put the client -
    // - back. A setback to where the simulation moved the player gives the server that as the failed tick's movement, -
    // - in the place of the tick's own, unless the tick's movement reached the server already -
    private void startSetback(ClientTickReport report, TickEnd end, boolean late) {
        if (!report.start().hasPosition() || this.user.getEncoderState() != ConnectionState.PLAY) {
            // - Without a player in a level there is nothing to put back -
            return;
        }
        this.setbackPhase = SetbackPhase.REQUESTED;
        int generation = ++this.setbackGeneration;
        this.setbackTickEnd = end.serverboundPackets();
        this.correctionAnsweredAt = -1L;
        this.failedAfterCorrection = null;
        this.setbacksRequested++;
        this.hold.setDroppingMovement(true);
        SetbackRequest.PredictedEnd predicted = this.predictedEnd(report, late);
        if (predicted != null && !late) {
            this.hold.substituteMovement(end.serverboundPackets(), this.substituteMovement(report, predicted));
        }
        SetbackRequest request = new SetbackRequest(
                generation, report.clientTick(), describeChecks(report), report.vehicle() != null, report.start(), end.arrivalNanos(), late, predicted);
        if (!this.setbacks.request(this, request)) {
            this.setbackSkipped(generation);
        }
    }

    // - Where the setback puts the player while setbacks.type is predicted: where the simulation moved it in the -
    // - failed tick, as far as that is somewhere a vanilla client could be (ClientTickReport.predictionUsable) and -
    // - the tick began with no teleport, respawn or configuration of the server on its way to the client, which would -
    // - have moved the player after the start the simulation went on from. Null when the setback puts the player back -
    // - where the server has it. A vehicle whose movement reached the server unjudged stays where the server has it -
    // - (see Setbacks), so its prediction is of no use then -
    private SetbackRequest.@Nullable PredictedEnd predictedEnd(ClientTickReport report, boolean late) {
        if (this.responses.settings().setbackType() != SetbackType.PREDICTED || !report.predictionUsable() || report.start().repositionPending()) {
            return null;
        }
        ClientTickReport.VehicleState vehicle = report.vehicle();
        if (vehicle == null) {
            return SetbackRequest.PredictedEnd.ofPlayer(report);
        }
        return late ? null : SetbackRequest.PredictedEnd.ofVehicle(vehicle);
    }

    // - The movement packet the server gets in place of the failed tick's own: the player's position packet with the -
    // - simulation's position, ground and collision state, which LocalPlayer.sendPosition sends, or, when the player -
    // - steered a vehicle, the vehicle's move with the simulation's position, rotation and ground state, which -
    // - LocalPlayer.sendChanges sends (Entity.getClientPositionAndRotation). The player's rotation stays what the -
    // - server has, as the rotation packets of the ticks that are set back are thrown away as well -
    private byte[] substituteMovement(ClientTickReport report, SetbackRequest.PredictedEnd predicted) {
        Vector3d position = new Vector3d(predicted.x(), predicted.y(), predicted.z());
        ClientTickReport.VehicleState vehicle = report.vehicle();
        PacketWrapper<?> packet = vehicle == null
                ? new WrapperPlayClientPlayerFlying(true, false, report.predictedOnGround(), report.predictedHorizontalCollision(), new Location(position, 0.0F, 0.0F))
                : new WrapperPlayClientVehicleMove(position, predicted.yRot(), predicted.xRot(), vehicle.predictedOnGround());
        Object buffer = ChannelHelper.pooledByteBuf(this.user.getChannel());
        try {
            writePacket(packet, buffer);
            return ByteBufHelper.copyBytes(buffer);
        } finally {
            ByteBufHelper.release(buffer);
        }
    }

    // - The checks a tick failed, for the log -
    private static String describeChecks(ClientTickReport report) {
        return report.flags().stream().map(Flag::check).distinct().map(check -> check.displayName()).collect(Collectors.joining(", "));
    }

    // - From the server thread: teleports the client to this position with this velocity and its own rotation, which -
    // - is where the server has the player, or, when predicted, where the simulation moved it in the failed tick -
    void correctPosition(int generation, double x, double y, double z, double velocityX, double velocityY, double velocityZ, boolean predicted) {
        this.runInEventLoop(() -> {
            if (!this.awaitsCorrection(generation) || predicted && this.serverRepositioned(generation, this.serverTeleportAt)) {
                return;
            }
            this.writeCorrection(PacketType.Play.Server.PLAYER_POSITION_AND_LOOK, new WrapperPlayServerPlayerPositionAndLook(
                    this.nextTeleportId(), new Vector3d(x, y, z), new Vector3d(velocityX, velocityY, velocityZ), 0.0F, 0.0F,
                    RelativeFlag.YAW.or(RelativeFlag.PITCH)));
            this.correctionSent();
            if (predicted) {
                this.predictedPositionCorrections++;
            } else {
                this.positionCorrections++;
            }
        });
    }

    // - From the server thread: puts the vehicle the client steers at this position with this rotation, which is -
    // - where the server has it, as the server itself puts a vehicle that moved wrongly -
    // - (ServerGamePacketListenerImpl.handleMoveVehicle), or, when predicted, where the simulation moved it in the -
    // - failed tick. That packet only moves the vehicle; its velocity would stay what the client had and what the -
    // - simulation estimated from the rejected movement, which differ, so both get this velocity -
    // - (ClientPacketListener.handleSetEntityMotion): none where the server has the vehicle, the simulation's where -
    // - it moved it -
    void correctVehicle(
            int generation, int vehicleId, double x, double y, double z, float yRot, float xRot, double velocityX, double velocityY, double velocityZ,
            boolean predicted
    ) {
        this.runInEventLoop(() -> {
            if (!this.awaitsCorrection(generation)
                    || predicted && this.serverRepositioned(generation, Math.max(this.serverVehicleMoveAt, this.serverTeleportAt))) {
                return;
            }
            this.writeCorrection(PacketType.Play.Server.VEHICLE_MOVE, new WrapperPlayServerVehicleMove(new Vector3d(x, y, z), yRot, xRot));
            this.writeCorrection(PacketType.Play.Server.ENTITY_VELOCITY, new WrapperPlayServerEntityVelocity(vehicleId, new Vector3d(velocityX, velocityY, velocityZ)));
            this.correctionSent();
            if (predicted) {
                this.predictedVehicleCorrections++;
            } else {
                this.vehicleCorrections++;
            }
        });
    }

    // - Whether the server put the player somewhere itself after the failed tick ended: a teleport of the server, or -
    // - a move of the vehicle the player steers, went out at this place among the serverbound packets, after the -
    // - tick's end packet. That puts the client where the server has it, Paper's own corrections of a movement that -
    // - moved wrongly included, which fire no event (ServerGamePacketListenerImpl.internalTeleport), and a correction -
    // - to where the simulation moved the player would undo it, so the setback is over then -
    private boolean serverRepositioned(int generation, long serverMoveAt) {
        if (serverMoveAt < this.setbackTickEnd) {
            return false;
        }
        this.logger.info("Not setting {} back to where the simulation moved it: the server put it somewhere itself after that tick", this.user.getName());
        this.setbacksSkipped++;
        this.endSetback(generation);
        return true;
    }

    // - From the server thread: the server teleported the player itself (see Setbacks), and ignores the client's -
    // - movement until the client answers the teleport, so that the setback is done here -
    void setbackTeleported(int generation) {
        this.runInEventLoop(() -> {
            if (generation == this.setbackGeneration && this.setbackPhase == SetbackPhase.REQUESTED) {
                this.serverTeleports++;
                this.endSetback(generation);
            }
        });
    }

    // - The player cannot be set back (see Setbacks); its movement reaches the server again -
    void setbackSkipped(int generation) {
        this.runInEventLoop(() -> {
            if (generation == this.setbackGeneration && this.setbackPhase == SetbackPhase.REQUESTED) {
                this.setbacksSkipped++;
                this.endSetback(generation);
            }
        });
    }

    // - Whether this setback still waits for its correction and the connection can take one. A setback that cannot -
    // - is over -
    private boolean awaitsCorrection(int generation) {
        if (generation != this.setbackGeneration || this.setbackPhase != SetbackPhase.REQUESTED) {
            return false;
        }
        if (this.state != State.SIMULATED || !ChannelHelper.isOpen(this.user.getChannel()) || this.user.getEncoderState() != ConnectionState.PLAY) {
            this.setbacksSkipped++;
            this.endSetback(generation);
            return false;
        }
        return true;
    }

    // - A correction goes into one of the connection's own bundles like any packet the simulation needs, so that the -
    // - simulation sees where among the client's ticks the client took it -
    private void writeCorrection(PacketTypeCommon type, PacketWrapper<?> correction) {
        this.beforeWrite(type, true);
        this.writeOwnPacket(correction);
    }

    // - The pong to the ping that ends the bundle with the correction ends the setback -
    private void correctionSent() {
        this.correctionInOpenBundle = true;
        this.setbackPhase = SetbackPhase.CORRECTED;
    }

    // - The client has taken the correction: its movement reaches the server again, unless a tick after the -
    // - correction failed as well, whose setback starts now -
    private void finishSetback(int generation) {
        if (generation != this.setbackGeneration || this.setbackPhase == SetbackPhase.NONE) {
            return;
        }
        FailedTick next = this.failedAfterCorrection;
        this.endSetback(generation);
        if (next != null) {
            this.startSetback(next.report(), next.end(), next.late());
        }
    }

    private void endSetback(int generation) {
        if (generation != this.setbackGeneration || this.setbackPhase == SetbackPhase.NONE) {
            return;
        }
        this.setbackPhase = SetbackPhase.NONE;
        this.correctionInOpenBundle = false;
        this.correctionPingAwaited = false;
        this.correctionAnsweredAt = -1L;
        this.failedAfterCorrection = null;
        this.hold.setDroppingMovement(false);
    }

    // - From the simulation thread: no more verdicts come, so nothing is held any more -
    void onSimulationStopped() {
        this.runInEventLoop(() -> {
            this.hold.disable();
            this.endSetback(this.setbackGeneration);
        });
    }

    // - From the server thread at the end of each server tick: lets what is held go on once the oldest packet waited -
    // - longer than allowed, also while the client sends nothing -
    void checkHold(long now) {
        long oldest = this.hold.oldestHeldNanos();
        long maximumHoldNanos = this.responses.settings().maximumHoldNanos();
        if (oldest != 0L && now - oldest > maximumHoldNanos) {
            this.runInEventLoop(() -> this.hold.releaseOverdue(System.nanoTime(), maximumHoldNanos));
        }
    }

    // - One line on what was held, kept from the server and set back so far; any thread -
    String holdSummary() {
        TickHold.Statistics held = this.hold.statistics();
        double averageMillis = held.releasedPackets() > 0L ? held.holdNanos() / NANOS_PER_MILLISECOND / held.releasedPackets() : 0.0;
        return String.format(Locale.ROOT,
                "%s: %d packets held now, %d held so far for %.2f ms on average and at most %.1f ms, %d movement packets and "
                        + "%d actions kept from the server, %d block predictions taken back, %d times let go unjudged; %d setbacks: "
                        + "%d teleports and %d vehicle corrections to where the simulation moved the player, with %d movement packets "
                        + "in place of the client's, %d teleports and %d vehicle corrections to where the server had it, %d teleports on "
                        + "the server, %d skipped",
                this.user.getName(), held.heldNow(), held.releasedPackets(), averageMillis, held.longestHoldNanos() / NANOS_PER_MILLISECOND,
                held.droppedMovement(), held.droppedActions(), this.predictionsAcknowledged, held.unjudgedReleases(), this.setbacksRequested,
                this.predictedPositionCorrections, this.predictedVehicleCorrections, held.substitutedMovement(), this.positionCorrections,
                this.vehicleCorrections, this.serverTeleports, this.setbacksSkipped);
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
                    started.handlePacket(packet.phase(), packet.direction(), packet.encodedPacket(), packet.handedInNanos());
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
            newReporter = new TickReporter(this.user, name, csvFile, this.logger, this.responses, this);
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

    // - From the server thread before PacketEvents is terminated: whatever is held goes on while PacketEvents' decoder, -
    // - which it goes on from, is still there -
    void stopHolding() {
        this.runInEventLoop(() -> {
            this.hold.disable();
            this.endSetback(this.setbackGeneration);
        });
    }

    // - On the event loop when the connection closed, or from the server thread when the plugin disables -
    void close() {
        this.runInEventLoop(this.hold::close);
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
