package io.github.hellotta.clauac.simulation.api;

public interface PlayerSimulation extends AutoCloseable {

    // - encodedPacket is the packet exactly as it travels on the wire after decompression: the packet id as a -
    // - VarInt followed by its payload. handedInNanos is when the server sent it or received it (System.nanoTime), -
    // - also for a packet handed in later than that. Calls for one connection must not overlap and must follow -
    // - network order -
    void handlePacket(ProtocolPhase phase, PacketDirection direction, byte[] encodedPacket, long handedInNanos);

    // - Like handlePacket, for a clientbound play packet that the plugin itself sends the client in the server's -
    // - stead: the pings behind its bundles, the corrections of its setbacks and its acknowledgements of block -
    // - predictions. The client takes such a packet like one of the server's. A correction puts the player, or the -
    // - vehicle it steers, where the server has it or where the simulation moved it in a tick that failed, while the -
    // - client keeps whether it stood on the ground in the ticks it played before it took the correction, which never -
    // - reached the server: the simulation gives the corrected player or vehicle the ground state of the place the -
    // - correction puts it instead -
    void handleOwnPacket(byte[] encodedPacket, long handedInNanos);

    // - What the simulation has cost so far and how far it is behind; may be called from any thread -
    SimulationStatistics statistics();

    @Override
    void close();
}
