package io.github.hellotta.clauac.simulation;

import io.github.hellotta.clauac.simulation.api.SimulationRuntime;
import io.github.hellotta.clauac.simulation.api.SimulationRuntimeFactory;
import java.io.PrintStream;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

// - Found by java.util.ServiceLoader in the isolated class loader -
public final class VanillaSimulationRuntimeFactory implements SimulationRuntimeFactory {

    @Override
    public SimulationRuntime create() {
        // - Bootstrap.bootStrap replaces System.out and System.err with streams that log through the runtime's -
        // - logger; the server's own streams are put back so that the rest of the server is unaffected -
        PrintStream out = System.out;
        PrintStream err = System.err;
        try {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return new VanillaSimulationRuntime();
    }
}
