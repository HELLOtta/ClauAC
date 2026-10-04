package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.netty.buffer.ByteBufHelper;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

// - Keeps the client's play packets from the server from a tick's first movement packet or action until the -
// - simulation has judged that tick, so that the server only applies movement and actions ClauAC has checked. It -
// - throws the movement of a tick away when the tick is to be set back, and each action that failed a check; a set -
// - back tick's movement can be replaced by the one the simulation gave it (see substituteMovement). The packets go -
// - on to the server in the order the client sent them: a packet a verdict cannot throw away goes on right away -
// - while nothing is held, and otherwise waits behind what is held. -
// - A held packet is copied and its buffer emptied, which the vanilla decoder after PacketEvents' passes over, and -
// - it goes on later from PacketEvents' decoder, past every packet listener, as if it came just then. -
// -
// - The simulation can fall behind; the packets then go on unjudged once the oldest has waited longer than the -
// - configured time, or once more is held than the limits below allow. Nothing is held across the client's switch -
// - to the configuration phase either: the server stops reading at that packet until it has switched protocols, so -
// - that everything behind it has to reach the server after it. Everything runs on the connection's event loop -
final class TickHold {

    // - The packets a tick's verdict can throw away: its movement, and the actions of its key handling -
    // - (Minecraft.handleKeybinds): attacks and interactions with entities, block actions, item uses on blocks and -
    // - in the air, and the hotbar slots it reports -
    private static final Set<PacketTypeCommon> MOVEMENT = Set.of(
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_FLYING,
            PacketType.Play.Client.VEHICLE_MOVE
    );
    // - The movement packets with a position: the server takes one of them per client tick and disconnects a client -
    // - that sends another before its tick end (ServerGamePacketListenerImpl.handleMovePlayer, -
    // - receivedPositionThisTick) -
    private static final Set<PacketTypeCommon> POSITIONS = Set.of(
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
    );
    private static final Set<PacketTypeCommon> ACTIONS = Set.of(
            PacketType.Play.Client.ATTACK,
            PacketType.Play.Client.INTERACT_ENTITY,
            PacketType.Play.Client.PLAYER_DIGGING,
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.USE_ITEM,
            PacketType.Play.Client.HELD_ITEM_CHANGE
    );
    // - The actions whose item the client uses up or changes on its own before the server answers -
    // - (MultiPlayerGameMode.interact, useItemOn and useItem): the server never learns of a thrown away one -
    private static final Set<PacketTypeCommon> ITEM_ACTIONS = Set.of(
            PacketType.Play.Client.INTERACT_ENTITY,
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.USE_ITEM
    );
    // - Both wait for their tick's verdict, and so does everything the client sent after them -
    private static final Set<PacketTypeCommon> JUDGED = union(MOVEMENT, ACTIONS);
    // - What one connection may hold at most. A client sends a few packets per tick, and even the packets of a second -
    // - with the simulation behind stay far below; a client flooding the server is not held back, so that the -
    // - server's own packet limiter sees the flood -
    private static final int MAXIMUM_HELD_PACKETS = 2048;
    private static final long MAXIMUM_HELD_BYTES = 4L * 1024L * 1024L;

    // - A held packet (its type and bytes, the packet id included), with the serverbound packets handed to the -
    // - simulation through it (see TickEnd), or a marker, whose action runs once the packets before it went on -
    private record Held(@Nullable PacketTypeCommon type, byte @Nullable [] packet, @Nullable Runnable marker, long sequence, long arrivedNanos) {
    }

    // - Movement the server gets in place of a set back tick's own (see substituteMovement): the place of the tick's -
    // - end packet among the serverbound packets (TickEnd.serverboundPackets) and the packet, id and payload -
    private record Substitute(long tickEnd, byte[] packet) {
    }

    // - What refuse kept from the server: the places of the refused actions that were still held, and whether one -
    // - of them used up or changed an item -
    record Refusal(Set<Long> packets, boolean itemRefused) {
    }

    // - What the hold did so far, for /clauac status -
    record Statistics(
            int heldNow, long oldestHeldNanos, long releasedPackets, long holdNanos, long longestHoldNanos, long droppedMovement, long droppedActions,
            long unjudgedReleases, long substitutedMovement
    ) {
    }

    private final User user;
    private final Deque<Held> held = new ArrayDeque<>();
    private long heldBytes;
    // - Off for a connection whose simulation did not see it from its start or has stopped: nothing is held then -
    private boolean enabled;
    // - Serverbound packets handed to the simulation so far, counted as TickEnd counts them -
    private long handedOver;
    // - Through which of them the simulation judged the client's ticks, and through which packets went on unjudged -
    private long judgedThrough;
    private long unjudgedThrough;
    private boolean droppingMovement;
    // - The movement of the client's packets through this one never reaches the server: they belong to ticks that -
    // - are set back -
    private long droppedMovementThrough;
    // - The actions that failed a check, by their place among the serverbound packets; each never reaches the server -
    private final Set<Long> refused = new HashSet<>();
    // - Substitutes waiting for the packets before their place to go on, in the order of their ticks -
    private final Deque<Substitute> substitutes = new ArrayDeque<>();
    // - What went to the server in the client tick the server is in, the one the next tick end the server gets ends: -
    // - whether a position did (see POSITIONS), and whether a substitute's did. The server takes one position per -
    // - client tick, so the client's own positions of that tick are thrown away after a substitute, and a substitute -
    // - after any position waits for the next client tick -
    private boolean positionInServerTick;
    private boolean substituteInServerTick;
    // - Substitutes that were to go to the server in a client tick that had a position already; each goes on right -
    // - behind the next tick end the server gets -
    private final Deque<byte[]> deferredSubstitutes = new ArrayDeque<>();
    // - Written on the event loop only; volatile for the server thread and /clauac status -
    private volatile long oldestHeldNanos;
    private volatile int heldNow;
    private volatile long releasedPackets;
    private volatile long holdNanos;
    private volatile long longestHoldNanos;
    private volatile long droppedMovement;
    private volatile long droppedActions;
    private volatile long unjudgedReleases;
    private volatile long substitutedMovement;

    // - Off until the connection turns out to be simulated from its start (see enable) -
    TickHold(User user) {
        this.user = user;
    }

    void enable() {
        this.enabled = true;
    }

    boolean enabled() {
        return this.enabled;
    }

    // - Every serverbound packet handed to the simulation, of every phase, in the order they are handed over -
    void countHandedOver() {
        this.handedOver++;
    }

    // - The serverbound packets handed to the simulation so far, as TickEnd counts them -
    long handedOver() {
        return this.handedOver;
    }

    // - A serverbound play packet whose bytes are final (a PacketEvents post task), right before it would go on to -
    // - the server -
    void onServerbound(PacketTypeCommon type, Object buffer, long now, long maximumHoldNanos) {
        if (!this.enabled) {
            return;
        }
        if (type == PacketType.Play.Client.CONFIGURATION_ACK) {
            this.releaseUnjudged();
            // - The play phase ends here: a substitute still waiting for a client tick would reach the server in the -
            // - configuration phase, and the next play phase starts with a client tick of its own -
            this.forgetDeferredSubstitutes();
            return;
        }
        // - A tick end that a deferred substitute waits for is held for a moment, so that the substitute goes on -
        // - right behind it (see wentToServer) -
        boolean substituteWaits = type == PacketType.Play.Client.CLIENT_TICK_END && !this.deferredSubstitutes.isEmpty();
        if (this.held.isEmpty() && !JUDGED.contains(type) && !substituteWaits) {
            // - The packet goes on to the server as it is -
            this.wentToServer(type);
            return;
        }
        byte[] packet = ByteBufHelper.copyBytes(buffer);
        ByteBufHelper.clear(buffer);
        this.held.addLast(new Held(type, packet, null, this.handedOver, now));
        this.heldBytes += packet.length;
        this.releaseJudged(now);
        if (this.held.size() > MAXIMUM_HELD_PACKETS || this.heldBytes > MAXIMUM_HELD_BYTES) {
            this.releaseUnjudged();
        } else {
            this.releaseOverdue(now, maximumHoldNanos);
        }
    }

    // - Whether packets of the tick whose verdict comes next went on before it: when the hold is off, or when they -
    // - were released unjudged. Asked before judged -
    boolean wentOnUnjudged() {
        return !this.enabled || this.unjudgedThrough > this.judgedThrough;
    }

    // - The simulation judged the client's ticks through this serverbound packet (TickEnd.serverboundPackets) -
    void judged(long through, long now) {
        this.judgedThrough = Math.max(this.judgedThrough, through);
        this.releaseJudged(now);
    }

    // - While a setback is under way, the client's movement does not reach the server -
    void setDroppingMovement(boolean dropping) {
        this.droppingMovement = dropping;
    }

    // - The movement of a tick that is set back, through its end packet (TickEnd.serverboundPackets), does not reach -
    // - the server either, even when a setback ends before its packets go on -
    void dropMovementThrough(long through) {
        this.droppedMovementThrough = Math.max(this.droppedMovementThrough, through);
    }

    // - The actions at these places among the serverbound packets failed a check: those still held never reach the -
    // - server. The others went on unjudged (see releaseUnjudged), and the server answers them itself. Asked right -
    // - before their tick is judged, which lets go of every one of them -
    Refusal refuse(Set<Long> packets) {
        Set<Long> kept = new HashSet<>();
        boolean itemRefused = false;
        for (Held entry : this.held) {
            if (entry.type() != null && ACTIONS.contains(entry.type()) && packets.contains(entry.sequence())) {
                kept.add(entry.sequence());
                itemRefused |= ITEM_ACTIONS.contains(entry.type());
            }
        }
        this.refused.addAll(kept);
        return new Refusal(Set.copyOf(kept), itemRefused);
    }

    // - Sends the server this movement in place of the movement of a tick that is set back, where the client sends -
    // - its own: right before the tick's end packet, at this place among the serverbound packets -
    // - (TickEnd.serverboundPackets), once the packets before it went on, or right away when nothing from that place -
    // - on is held any more, which puts it into the client tick the server is in by then. The server takes one -
    // - position per client tick (see POSITIONS): the client's own positions in the client tick of a substitute are -
    // - thrown away, and a substitute for a client tick that has a position already waits for the next. No verdict -
    // - throws it away, and nothing hands it to the simulation, which only follows what the client itself sent -
    void substituteMovement(long tickEnd, byte[] packet) {
        Held head = this.held.peekFirst();
        if (head == null || head.sequence() > tickEnd) {
            this.sendSubstitute(packet);
            return;
        }
        this.substitutes.addLast(new Substitute(tickEnd, packet));
    }

    // - Runs the action once everything held now went on: right away when nothing is held -
    void mark(Runnable action, long now) {
        if (this.held.isEmpty()) {
            action.run();
            return;
        }
        this.held.addLast(new Held(null, null, action, this.handedOver, now));
        this.updateOldest();
    }

    // - Lets everything held go on once the oldest packet waited longer than allowed -
    void releaseOverdue(long now, long maximumHoldNanos) {
        Held oldest = this.held.peekFirst();
        if (oldest != null && now - oldest.arrivedNanos() > maximumHoldNanos) {
            this.releaseUnjudged();
        }
    }

    // - The simulation stopped: whatever is held goes on, and nothing is held any more. A substitute still waiting -
    // - for a client tick goes nowhere: once nothing is held, the client's own positions reach the server unchecked, -
    // - and it could no longer keep to the server's one position per client tick -
    void disable() {
        this.releaseUnjudged();
        this.forgetDeferredSubstitutes();
        this.enabled = false;
    }

    // - The connection closed: what is held can go nowhere -
    void close() {
        this.held.clear();
        this.refused.clear();
        this.substitutes.clear();
        this.forgetDeferredSubstitutes();
        this.heldBytes = 0L;
        this.enabled = false;
        this.updateOldest();
    }

    // - When the oldest held packet arrived, 0 while nothing is held; any thread -
    long oldestHeldNanos() {
        return this.oldestHeldNanos;
    }

    Statistics statistics() {
        return new Statistics(this.heldNow, this.oldestHeldNanos, this.releasedPackets, this.holdNanos, this.longestHoldNanos,
                this.droppedMovement, this.droppedActions, this.unjudgedReleases, this.substitutedMovement);
    }

    // - The packets at the head go on as far as the verdicts allow: everything through the last judged tick, and -
    // - after it whatever no verdict can throw away, up to the next packet one can -
    private void releaseJudged(long now) {
        Held head;
        while ((head = this.held.peekFirst()) != null
                && (head.sequence() <= this.judgedThrough || head.type() == null || !JUDGED.contains(head.type()))) {
            this.release(this.held.removeFirst(), now);
        }
        if (this.held.isEmpty()) {
            // - Nothing a substitute could still have to wait for is held: its tick's end went on with the rest -
            this.releaseSubstitutesThrough(Long.MAX_VALUE);
        }
        this.updateOldest();
    }

    // - Everything held goes on without waiting for its verdicts, which then come too late to keep its movement from -
    // - the server -
    private void releaseUnjudged() {
        if (this.held.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        while (!this.held.isEmpty()) {
            Held entry = this.held.removeFirst();
            this.unjudgedThrough = Math.max(this.unjudgedThrough, entry.sequence());
            this.release(entry, now);
        }
        this.releaseSubstitutesThrough(Long.MAX_VALUE);
        this.unjudgedReleases++;
        this.updateOldest();
    }

    private void release(Held entry, long now) {
        this.releaseSubstitutesThrough(entry.sequence());
        Runnable marker = entry.marker();
        if (marker != null) {
            marker.run();
            return;
        }
        byte[] packet = entry.packet();
        if (packet == null) {
            throw new IllegalStateException("a held entry without a marker has no packet");
        }
        this.heldBytes -= packet.length;
        long heldFor = now - entry.arrivedNanos();
        this.releasedPackets++;
        this.holdNanos += heldFor;
        this.longestHoldNanos = Math.max(this.longestHoldNanos, heldFor);
        if ((this.droppingMovement || entry.sequence() <= this.droppedMovementThrough) && MOVEMENT.contains(entry.type())) {
            this.droppedMovement++;
            return;
        }
        if (this.substituteInServerTick && POSITIONS.contains(entry.type())) {
            // - The server took a substitute's position in this client tick already -
            this.droppedMovement++;
            return;
        }
        if (ACTIONS.contains(entry.type()) && this.refused.remove(entry.sequence())) {
            this.droppedActions++;
            return;
        }
        this.sendToServer(packet);
        if (POSITIONS.contains(entry.type())) {
            this.positionInServerTick = true;
        }
        this.wentToServer(entry.type());
    }

    // - The substitutes whose tick ended with or before this place among the serverbound packets go on first -
    private void releaseSubstitutesThrough(long sequence) {
        Substitute next;
        while ((next = this.substitutes.peekFirst()) != null && next.tickEnd() <= sequence) {
            this.sendSubstitute(this.substitutes.removeFirst().packet());
        }
    }

    // - A substitute goes on now unless the client tick the server is in has a position already -
    private void sendSubstitute(byte[] packet) {
        if (this.positionInServerTick) {
            this.deferredSubstitutes.addLast(packet);
            return;
        }
        this.sendToServer(packet);
        this.substitutedMovement++;
        this.positionInServerTick = true;
        this.substituteInServerTick = true;
    }

    // - The server got a packet of this type. A tick end ends the client tick the server is in, and the oldest -
    // - substitute waiting for the next one goes on right behind it -
    private void wentToServer(@Nullable PacketTypeCommon type) {
        if (type != PacketType.Play.Client.CLIENT_TICK_END) {
            return;
        }
        this.positionInServerTick = false;
        this.substituteInServerTick = false;
        byte[] deferred = this.deferredSubstitutes.pollFirst();
        if (deferred != null) {
            this.sendSubstitute(deferred);
        }
    }

    // - Neither a substitute nor a client tick of the server is in play any more -
    private void forgetDeferredSubstitutes() {
        this.deferredSubstitutes.clear();
        this.positionInServerTick = false;
        this.substituteInServerTick = false;
    }

    // - The packet goes on from PacketEvents' decoder, past every packet listener, as if it came just then -
    private void sendToServer(byte[] packet) {
        Object buffer = ChannelHelper.pooledByteBuf(this.user.getChannel());
        ByteBufHelper.writeBytes(buffer, packet);
        this.user.receivePacketSilently(buffer);
    }

    private static Set<PacketTypeCommon> union(Set<PacketTypeCommon> first, Set<PacketTypeCommon> second) {
        Set<PacketTypeCommon> union = new HashSet<>(first);
        union.addAll(second);
        return Set.copyOf(union);
    }

    private void updateOldest() {
        Held oldest = this.held.peekFirst();
        this.oldestHeldNanos = oldest != null ? oldest.arrivedNanos() : 0L;
        this.heldNow = this.held.size();
    }
}
