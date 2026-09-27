package io.github.hellotta.clauac;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.hellotta.clauac.bridge.SimulationBridge;
import io.github.hellotta.clauac.runtime.SimulationRuntimeLoader;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class ClauACPlugin extends JavaPlugin implements Listener {

    private @Nullable CompletableFuture<SimulationRuntimeLoader.LoadedRuntime> runtimeLoading;
    private @Nullable SimulationBridge bridge;

    @Override
    public void onLoad() {
        // - PacketEvents is bundled, so this plugin owns the instance and has to build and load it itself -
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .reEncodeByDefault(false) // Listeners must mark a packet as modified explicitly before it is re-encoded
                .checkForUpdates(false); // Server owners cannot swap out a bundled copy, so update notices would only mislead them
        PacketEvents.getAPI().load();
        // - Started as early as possible: the vanilla runtime has to be ready before the first player connects -
        this.runtimeLoading = SimulationRuntimeLoader.loadAsync(
                this.getDataPath(), this.getServer().getMinecraftVersion(), this.getClass().getClassLoader(), this.getSLF4JLogger());
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();
        SimulationBridge simulationBridge = new SimulationBridge(this.getSLF4JLogger(), this.getDataPath().resolve("reports"));
        this.bridge = simulationBridge;
        // - Runs after every other listener, so that the simulation sees packets exactly as they are sent -
        PacketEvents.getAPI().getEventManager().registerListener(simulationBridge, PacketListenerPriority.MONITOR);
        this.getServer().getPluginManager().registerEvents(this, this);
        ClauACCommand command = new ClauACCommand(simulationBridge);
        PluginCommand pluginCommand = Objects.requireNonNull(this.getCommand("clauac"), "plugin.yml declares the clauac command");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
        Objects.requireNonNull(this.runtimeLoading).whenComplete((loaded, failure) -> {
            if (failure != null) {
                this.getSLF4JLogger().error("The vanilla runtime could not be started; no connection is simulated", failure);
                simulationBridge.setRuntimeFailure("the vanilla runtime could not be started (" + failure + ")");
            } else {
                simulationBridge.setRuntime(loaded.runtime());
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerTickEnd(ServerTickEndEvent event) {
        SimulationBridge simulationBridge = this.bridge;
        if (simulationBridge != null) {
            simulationBridge.onServerTickEnd(this.getServer().getOnlinePlayers());
        }
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
        if (this.bridge != null) {
            this.bridge.closeAll();
        }
        // - A runtime that is still starting is closed as soon as it has started -
        CompletableFuture<SimulationRuntimeLoader.LoadedRuntime> loading = this.runtimeLoading;
        if (loading != null) {
            loading.thenAccept(this::closeRuntime);
        }
    }

    private void closeRuntime(SimulationRuntimeLoader.LoadedRuntime loaded) {
        try {
            loaded.close();
        } catch (IOException exception) {
            this.getSLF4JLogger().error("Could not close the vanilla runtime's class loader", exception);
        }
    }
}
