package io.github.hellotta.clauac.simulation.api;

import java.util.UUID;

public interface SimulationRuntime extends AutoCloseable {

    // - Minecraft version of the vanilla code running the simulation -
    String minecraftVersion();

    // - How many threads simulate the connections, all of them together -
    int simulationThreads();

    // - Whether a packet with this network id must be forwarded; ids are the vanilla protocol's own ids -
    boolean isRelevant(ProtocolPhase phase, PacketDirection direction, int packetId);

    // - Creates the simulation of one connection; it has to receive every relevant packet of that connection -
    // - in network order, starting with the first configuration packet -
    PlayerSimulation createPlayer(UUID profileId, String profileName, SimulationListener listener);

    // - Stops the simulation threads and the vanilla runtime's own executors; every player simulation must be -
    // - closed before -
    @Override
    void close();
}
