package io.github.hellotta.clauac.simulation.api;

public interface PlayerSimulation extends AutoCloseable {

    // - encodedPacket is the packet exactly as it travels on the wire after decompression: the packet id as a -
    // - VarInt followed by its payload. handedInNanos is when the server sent it or received it (System.nanoTime), -
    // - also for a packet handed in later than that. Calls for one connection must not overlap and must follow -
    // - network order -
    void handlePacket(ProtocolPhase phase, PacketDirection direction, byte[] encodedPacket, long handedInNanos);

    // - What the simulation has cost so far and how far it is behind; may be called from any thread -
    SimulationStatistics statistics();

    @Override
    void close();
}
