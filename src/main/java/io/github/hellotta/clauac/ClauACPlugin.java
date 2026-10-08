package io.github.hellotta.clauac;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import io.github.hellotta.clauac.bridge.OwnAnswerConsumer;
import io.github.hellotta.clauac.bridge.Setbacks;
import io.github.hellotta.clauac.bridge.SimulationBridge;
import io.github.hellotta.clauac.response.ClauACSettings;
import io.github.hellotta.clauac.response.Responses;
import io.github.hellotta.clauac.runtime.SimulationRuntimeLoader;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

public final class ClauACPlugin extends JavaPlugin implements Listener {

    private @Nullable CompletableFuture<SimulationRuntimeLoader.LoadedRuntime> runtimeLoading;
    private @Nullable SimulationBridge bridge;
    private @Nullable Responses responses;
    private @Nullable Setbacks setbacks;

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
        this.saveDefaultConfig();
        Responses newResponses = new Responses(this, this.getSLF4JLogger(), ClauACSettings.load(this.getConfig(), this.getSLF4JLogger()));
        this.responses = newResponses;
        Setbacks newSetbacks = new Setbacks(this, this.getSLF4JLogger());
        this.setbacks = newSetbacks;
        SimulationBridge simulationBridge = new SimulationBridge(this.getSLF4JLogger(), this.getDataPath().resolve("reports"), newResponses, newSetbacks);
        this.bridge = simulationBridge;
        // - Runs after every other listener, so that the simulation sees packets exactly as they are sent -
        PacketEvents.getAPI().getEventManager().registerListener(simulationBridge, PacketListenerPriority.MONITOR);
        // - Decides the final state of the answers to ClauAC's own pings and teleports: they go no further than the -
        // - simulation -
        PacketEvents.getAPI().getEventManager().registerListener(new OwnAnswerConsumer(simulationBridge), PacketListenerPriority.HIGHEST);
        this.getServer().getPluginManager().registerEvents(this, this);
        ClauACCommand command = new ClauACCommand(simulationBridge, newResponses, this::reloadSettings);
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

    // - Reads config.yml again for /clauac reload; returns the file's path for the confirmation -
    private String reloadSettings() {
        this.reloadConfig();
        Objects.requireNonNull(this.responses, "the plugin is enabled").reload(ClauACSettings.load(this.getConfig(), this.getSLF4JLogger()));
        return this.getDataPath().resolve("config.yml").toString();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Responses current = this.responses;
        if (current != null) {
            current.onJoin(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Responses current = this.responses;
        if (current != null) {
            current.onQuit(event.getPlayer().getUniqueId());
        }
        Setbacks currentSetbacks = this.setbacks;
        if (currentSetbacks != null) {
            currentSetbacks.onQuit(event.getPlayer().getUniqueId());
        }
    }

    // - The server puts players somewhere itself, which a setback must not undo (see Setbacks) -
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Setbacks current = this.setbacks;
        if (current != null) {
            current.onReposition(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Setbacks current = this.setbacks;
        if (current != null) {
            current.onReposition(event.getPlayer().getUniqueId());
        }
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
        if (this.responses != null) {
            this.responses.close();
        }
        // - The held packets go on from PacketEvents' decoder, which terminating PacketEvents removes -
        if (this.bridge != null) {
            this.bridge.stopHolding();
        }
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
