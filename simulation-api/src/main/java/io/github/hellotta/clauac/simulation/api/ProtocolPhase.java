package io.github.hellotta.clauac.simulation.api;

// - Connection phases whose packets the simulation understands; login and status traffic is never forwarded -
public enum ProtocolPhase {
    CONFIGURATION,
    PLAY
}
