package io.github.hellotta.clauac.simulation;

import com.mojang.logging.LogUtils;
import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.PlayerSimulation;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import io.github.hellotta.clauac.simulation.api.SimulationListener;
import io.github.hellotta.clauac.simulation.api.SimulationRuntime;
import io.github.hellotta.clauac.simulation.registry.ServerRegistryCache;
import io.github.hellotta.clauac.simulation.session.ClientSession;
import io.github.hellotta.clauac.simulation.session.RelevantPackets;
import io.github.hellotta.clauac.simulation.session.SimulationLimits;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.util.Util;
import org.slf4j.Logger;

// - The vanilla runtime after Minecraft's bootstrap: every connection's simulation shares its registries and its -
// - simulation threads -
final class VanillaSimulationRuntime implements SimulationRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10L;
    private final RelevantPackets relevantPackets = new RelevantPackets();
    private final ServerRegistryCache registryCache = new ServerRegistryCache();
    private final int threadCount;
    private final ExecutorService simulationThreads;
    private final SimulationLimits limits;

    VanillaSimulationRuntime() {
        // - Half of the processors, so that simulating never takes all of them from the server -
        this.threadCount = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.simulationThreads = Executors.newFixedThreadPool(this.threadCount, new SimulationThreadFactory());
        this.limits = SimulationLimits.fromSystemProperties(LOGGER);
        LOGGER.info("Simulation limits: {}", this.limits.describe());
    }

    @Override
    public String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    @Override
    public int simulationThreads() {
        return this.threadCount;
    }

    @Override
    public boolean isRelevant(ProtocolPhase phase, PacketDirection direction, int packetId) {
        return this.relevantPackets.isRelevant(phase, direction, packetId);
    }

    @Override
    public PlayerSimulation createPlayer(UUID profileId, String profileName, SimulationListener listener) {
        return new ClientSession(profileId, profileName, listener, this.registryCache, this.simulationThreads, this.limits);
    }

    @Override
    public void close() {
        this.simulationThreads.shutdown();
        try {
            if (!this.simulationThreads.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("Simulation threads did not finish within {} seconds, interrupting them", SHUTDOWN_TIMEOUT_SECONDS);
                this.simulationThreads.shutdownNow();
            }
        } catch (InterruptedException exception) {
            this.simulationThreads.shutdownNow();
            Thread.currentThread().interrupt();
        }
        Util.shutdownExecutors();
    }

    private static final class SimulationThreadFactory implements ThreadFactory {

        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "ClauAC Simulation #" + this.nextId.getAndIncrement());
            thread.setDaemon(true);
            // - Vanilla code started from these threads must resolve classes and resources in the isolated runtime -
            thread.setContextClassLoader(VanillaSimulationRuntime.class.getClassLoader());
            thread.setUncaughtExceptionHandler((failedThread, throwable) -> LOGGER.error("Uncaught exception in {}", failedThread.getName(), throwable));
            return thread;
        }
    }
}
