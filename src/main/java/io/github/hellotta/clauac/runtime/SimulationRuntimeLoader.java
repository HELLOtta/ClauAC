package io.github.hellotta.clauac.runtime;

import io.github.hellotta.clauac.simulation.api.SimulationRuntime;
import io.github.hellotta.clauac.simulation.api.SimulationRuntimeFactory;
import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;

// - Starts the isolated vanilla runtime on its own thread: preparing its files and running Minecraft's bootstrap -
// - takes seconds, which must never block a server thread -
public final class SimulationRuntimeLoader {

    // - The started runtime together with the class loader it lives in; closing it stops both -
    public record LoadedRuntime(SimulationRuntime runtime, URLClassLoader classLoader) implements AutoCloseable {

        @Override
        public void close() throws IOException {
            this.runtime.close();
            this.classLoader.close();
        }
    }

    private SimulationRuntimeLoader() {
    }

    public static CompletableFuture<LoadedRuntime> loadAsync(Path dataDirectory, String serverMinecraftVersion, ClassLoader pluginClassLoader, Logger logger) {
        CompletableFuture<LoadedRuntime> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                result.complete(load(dataDirectory, serverMinecraftVersion, pluginClassLoader, logger));
            } catch (Throwable throwable) {
                result.completeExceptionally(throwable);
                if (throwable instanceof Error error) {
                    throw error;
                }
            }
        }, "ClauAC Runtime Loader");
        thread.setDaemon(true);
        thread.start();
        return result;
    }

    private static LoadedRuntime load(Path dataDirectory, String serverMinecraftVersion, ClassLoader pluginClassLoader, Logger logger) throws IOException {
        long start = System.nanoTime();
        VanillaRuntimeFiles.Layout layout = VanillaRuntimeFiles.prepare(dataDirectory, logger);
        if (!layout.minecraftVersion().equals(serverMinecraftVersion)) {
            throw new IOException("The simulation was built for Minecraft " + layout.minecraftVersion()
                    + ", but the server runs " + serverMinecraftVersion);
        }
        IsolatedRuntimeClassLoader classLoader = new IsolatedRuntimeClassLoader(layout.classPath(), pluginClassLoader);
        Thread currentThread = Thread.currentThread();
        ClassLoader previousContextLoader = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(classLoader);
        try {
            SimulationRuntimeFactory factory = ServiceLoader.load(SimulationRuntimeFactory.class, classLoader)
                    .findFirst()
                    .orElseThrow(() -> new IOException("The simulation jar provides no " + SimulationRuntimeFactory.class.getName()));
            SimulationRuntime runtime = factory.create();
            logger.info("Vanilla {} runtime started in {} ms from {} class path entries, simulating on {} threads",
                    runtime.minecraftVersion(), (System.nanoTime() - start) / 1_000_000L, layout.classPath().size(), runtime.simulationThreads());
            return new LoadedRuntime(runtime, classLoader);
        } catch (RuntimeException | IOException | Error failure) {
            classLoader.close();
            throw failure;
        } finally {
            currentThread.setContextClassLoader(previousContextLoader);
        }
    }
}
