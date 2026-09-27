package io.github.hellotta.clauac.simulation.api;

// - What a client tick can fail. Every MISMATCHED tick names at least one of these, each with what exactly failed -
// - (see Flag); the plugin configures its responses per check under the display name -
public enum Check {
    // - The player did not move the way the vanilla client moves it: its position, ground, collision, sprinting, -
    // - flying or gliding differs from what the same keys and rotation give, or a position was (not) sent where the -
    // - vanilla client sends one -
    SIMULATION("Simulation"),
    // - The vehicle the player steers did not move the way the vanilla client moves it -
    VEHICLE("Vehicle"),
    // - The client sent a packet no vanilla client sends in its situation, or one the sandbox could not decode or apply -
    BAD_PACKETS("BadPackets"),
    // - The client ended more ticks than the simulation takes on (see the tick budget) -
    TICK_RATE("TickRate"),
    // - The client ended its ticks faster than the timer of a vanilla client runs: its ticks got further ahead of the -
    // - real time since it last answered one of the server's packets than a vanilla client's get when it catches up -
    TIMER("Timer"),
    // - The client held back its answers to the server's packets until the older half was applied without them -
    PINGS("Pings"),
    // - The simulation itself failed during the tick; nothing the client sent could be checked -
    SIMULATION_FAILURE("SimulationFailure");

    private final String displayName;

    Check(String displayName) {
        this.displayName = displayName;
    }

    // - The name alerts show and the configuration uses -
    public String displayName() {
        return this.displayName;
    }
}
