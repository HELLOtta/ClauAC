package io.github.hellotta.clauac.simulation.api;

// - Receives the results of one connection's simulation. Calls come from the runtime's simulation threads, never -
// - two at a time for the same connection, and in the order of the client's ticks -
public interface SimulationListener {

    // - simulationNanos is the tick's time as SimulationStatistics counts it: what the simulation spent on the -
    // - connection since the previous tick, the tick itself included. end tells where the tick ended in the -
    // - connection -
    void onClientTick(ClientTickReport report, long simulationNanos, TickEnd end);

    // - The simulation found that its items differ from the client's, and cannot know the client's. The server -
    // - should send the client its whole inventory and open menu again, as Paper's Player.updateInventory does; the -
    // - simulation takes over those contents when they arrive. Requested again only if the client closed the menu -
    // - before they arrived -
    void onInventoryResyncNeeded();

    // - A problem that stops the simulation of this connection; no further ticks are reported afterwards -
    void onSimulationFailure(String message, Throwable cause);
}
