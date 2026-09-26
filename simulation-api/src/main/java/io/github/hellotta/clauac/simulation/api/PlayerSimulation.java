package io.github.hellotta.clauac.simulation.api;

public interface PlayerSimulation extends AutoCloseable {

    // - encodedPacket is the packet exactly as it travels on the wire after decompression: the packet id as a -
    // - VarInt followed by its payload. Calls for one connection must not overlap and must follow network order -
    void handlePacket(ProtocolPhase phase, PacketDirection direction, byte[] encodedPacket);

    @Override
    void close();
}
