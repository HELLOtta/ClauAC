package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.network.protocol.Packet;
import org.jspecify.annotations.Nullable;

// - Clientbound packets the server sent that the client has not provably processed yet, in network order. The -
// - client processes received packets before each of its ticks, and answers some of them right away (a ping with -
// - a pong, a teleport with its acceptance). Such an answer proves that the client processed every packet up to -
// - the answered one, so they are released up to there, and never earlier. Only the connection's tasks change it; -
// - its size and bytes may be read from any thread -
final class PendingClientbound {

    // - sentAt is when the packet was handed to the simulation (System.nanoTime), right before it was written -
    record PendingPacket(ProtocolPhase phase, Packet<?> packet, byte[] encodedPacket, long sentAt) {
    }

    private final Deque<PendingPacket> packets = new ArrayDeque<>();
    private volatile int count;
    private volatile long bytes;

    void add(PendingPacket packet) {
        this.packets.addLast(packet);
        this.count++;
        this.bytes += packet.encodedPacket().length;
    }

    private PendingPacket removeFirst() {
        PendingPacket removed = this.packets.removeFirst();
        this.count--;
        this.bytes -= removed.encodedPacket().length;
        return removed;
    }

    boolean isEmpty() {
        return this.packets.isEmpty();
    }

    // - The first pending packet that is the answered one, or null when none is -
    @Nullable PendingPacket findFirst(Predicate<Packet<?>> answeredPacket) {
        for (PendingPacket pending : this.packets) {
            if (answeredPacket.test(pending.packet())) {
                return pending;
            }
        }
        return null;
    }

    // - The packets before the first answered one, excluding it -
    List<PendingPacket> peekBefore(PendingPacket answered) {
        List<PendingPacket> before = new ArrayList<>();
        for (PendingPacket pending : this.packets) {
            if (pending == answered) {
                return before;
            }
            before.add(pending);
        }
        throw new IllegalArgumentException("The packet is not pending");
    }

    // - Removes and returns the packets up to and including the first answered one. Returns an empty list when no -
    // - pending packet is the answered one, leaving everything pending -
    List<PendingPacket> takeThrough(Predicate<Packet<?>> answeredPacket) {
        PendingPacket answered = this.findFirst(answeredPacket);
        if (answered == null) {
            return List.of();
        }
        List<PendingPacket> released = new ArrayList<>();
        PendingPacket next;
        do {
            next = this.removeFirst();
            released.add(next);
        } while (next != answered);
        return released;
    }

    // - Removes and returns the leading packets that satisfy the condition -
    List<PendingPacket> takeLeading(Predicate<PendingPacket> condition) {
        List<PendingPacket> released = new ArrayList<>();
        while (!this.packets.isEmpty() && condition.test(this.packets.peekFirst())) {
            released.add(this.removeFirst());
        }
        return released;
    }

    // - Removes and returns the oldest packets until no more than these bytes and packets are left -
    List<PendingPacket> takeOldest(long bytesLeft, int packetsLeft) {
        List<PendingPacket> released = new ArrayList<>();
        while (!this.packets.isEmpty() && (this.bytes > bytesLeft || this.count > packetsLeft)) {
            released.add(this.removeFirst());
        }
        return released;
    }

    int size() {
        return this.count;
    }

    // - The encoded size of the pending packets -
    long bytes() {
        return this.bytes;
    }

    void clear() {
        this.packets.clear();
        this.count = 0;
        this.bytes = 0L;
    }
}
