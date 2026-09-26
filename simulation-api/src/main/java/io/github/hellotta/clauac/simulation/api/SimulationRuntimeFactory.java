package io.github.hellotta.clauac.simulation.api;

// - Service implemented inside the isolated vanilla runtime and discovered with java.util.ServiceLoader -
public interface SimulationRuntimeFactory {

    // - Boots Minecraft's registries inside the isolated class loader; takes several seconds, so never call it -
    // - on a server thread -
    SimulationRuntime create();
}
