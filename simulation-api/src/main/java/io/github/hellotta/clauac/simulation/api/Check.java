package io.github.hellotta.clauac.simulation.api;

// - What a client tick can fail. Every MISMATCHED tick names at least one of these, each with what exactly failed -
// - (see Flag); the plugin configures its responses per check under the display name. A check either concerns the -
// - player's movement or its actions: what the client's key handling did in the tick, its attacks and interactions -
// - with entities, the blocks it broke or used an item on, and the items it used -
public enum Check {
    // - The player did not move the way the vanilla client moves it: its position, ground, collision, sprinting, -
    // - flying or gliding differs from what the same keys and rotation give, or a position was (not) sent where the -
    // - vanilla client sends one -
    SIMULATION("Simulation", false),
    // - The vehicle the player steers did not move the way the vanilla client moves it -
    VEHICLE("Vehicle", false),
    // - The client sent a packet no vanilla client sends in its situation, or one the sandbox could not decode or apply -
    BAD_PACKETS("BadPackets", false),
    // - The client ended more ticks than the simulation takes on (see the tick budget) -
    TICK_RATE("TickRate", false),
    // - The client ended its ticks faster than the timer of a vanilla client runs: its ticks got further ahead of the -
    // - real time since it last answered one of the server's packets than a vanilla client's get when it catches up -
    TIMER("Timer", false),
    // - The client held back its answers to the server's packets until the older half was applied without them -
    PINGS("Pings", false),
    // - The client acted on an entity or a block farther away than the player reaches -
    REACH("Reach", true),
    // - The client acted on an entity or a block its crosshair did not point at: one behind a block or another -
    // - entity, or one away from where the player looked; or it used an item facing another way than the player -
    HITBOX("Hitbox", true),
    // - The client acted when a vanilla client does not: while it was using an item, while its hands were busy -
    // - paddling a boat, as a spectator, while breaking a block, outside the world border, with an item that cannot -
    // - do what the client did with it, or after selecting another hotbar slot in the middle of its key handling -
    INTERACTION("Interaction", true),
    // - The client finished breaking a block before its breaking progress, the progress of the vanilla client with -
    // - the same tool and effects in the same place, reached the whole block -
    FAST_BREAK("FastBreak", true),
    // - The client left out the swing of its main hand (ServerboundSwingPacket) a vanilla client sends right after -
    // - every attack, every start and finish of breaking a block and every stab, which the server shows the other -
    // - players as the swing of the player's arm -
    NO_SWING("NoSwing", true),
    // - The simulation itself failed during the tick; nothing the client sent could be checked -
    SIMULATION_FAILURE("SimulationFailure", false);

    private final String displayName;
    private final boolean concernsActions;

    Check(String displayName, boolean concernsActions) {
        this.displayName = displayName;
        this.concernsActions = concernsActions;
    }

    // - The name alerts show and the configuration uses -
    public String displayName() {
        return this.displayName;
    }

    // - Whether the check concerns the tick's actions (a single packet of the client, see Flag) rather than the -
    // - movement -
    public boolean concernsActions() {
        return this.concernsActions;
    }
}
