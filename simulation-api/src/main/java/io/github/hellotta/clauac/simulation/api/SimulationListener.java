package io.github.hellotta.clauac.simulation.api;

// - Receives the results of one connection's simulation. Calls come from the runtime's simulation threads, never -
// - two at a time for the same connection, and in the order of the client's ticks -
public interface SimulationListener {

    // - simulationNanos is the tick's time as SimulationStatistics counts it: what the simulation spent on the -
    // - connection since the previous tick, the tick itself included. end tells where the tick ended in the -
    // - connection -
    void onClientTick(ClientTickReport report, long simulationNanos, TickEnd end);

    // - Every action of the tick that ends at end passed its checks, which the simulation runs during the tick's key -
    // - handling, before the movement: no later part of the tick can fail them, and the tick's report, which -
    // - onClientTick brings afterwards, has no flag of a check that concerns actions. Comes only once the reports of -
    // - every earlier tick went to onClientTick. previousEnd is where the client's previous tick ended, counted as -
    // - TickEnd.serverboundPackets counts (0 before the first) -
    void onActionsPassed(TickEnd end, long previousEnd);

    // - The tick that ends at end matched: its report, which onClientTick brings afterwards, is MATCHED and has no -
    // - flag. Comes as soon as the simulation knows it, right after the local player's tick, while what the client -
    // - ticks after the player still has to be simulated; only once the reports of every earlier tick went to -
    // - onClientTick. previousEnd as for onActionsPassed -
    void onTickMatched(TickEnd end, long previousEnd);

    // - The simulation found that its items differ from the client's, and cannot know the client's. The server -
    // - should send the client its whole inventory and open menu again, as Paper's Player.updateInventory does; the -
    // - simulation takes over those contents when they arrive. Requested again only if the client closed the menu -
    // - before they arrived -
    void onInventoryResyncNeeded();

    // - A problem that stops the simulation of this connection; no further ticks are reported afterwards -
    void onSimulationFailure(String message, Throwable cause);
}
