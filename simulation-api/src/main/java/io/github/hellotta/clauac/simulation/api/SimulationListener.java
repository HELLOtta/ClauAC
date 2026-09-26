package io.github.hellotta.clauac.simulation.api;

// - Receives the results of one connection's simulation. Calls come from the runtime's simulation threads, never -
// - two at a time for the same connection, and in the order of the client's ticks -
public interface SimulationListener {

    void onClientTick(ClientTickReport report);

    // - A problem that stops the simulation of this connection; no further ticks are reported afterwards -
    void onSimulationFailure(String message, Throwable cause);
}
