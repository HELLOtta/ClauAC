package io.github.hellotta.clauac.simulation.api;

public enum TickOutcome {
    // - The simulated movement is exactly what the client reported -
    MATCHED,
    // - The simulated movement differs from what the client reported -
    MISMATCHED,
    // - The client did not simulate its player this tick (not loaded yet, outside ticking chunks, dead, riding) -
    NOT_SIMULATED,
    // - Something outside what the simulation reproduces influenced this tick; see the report's notes -
    UNVERIFIED
}
