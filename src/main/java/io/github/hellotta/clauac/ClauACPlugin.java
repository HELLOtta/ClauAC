package io.github.hellotta.clauac;

import com.github.retrooper.packetevents.PacketEvents;
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder;
import org.bukkit.plugin.java.JavaPlugin;

public final class ClauACPlugin extends JavaPlugin {

    @Override
    public void onLoad() {
        // - PacketEvents is bundled, so this plugin owns the instance and has to build and load it itself -
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this));
        PacketEvents.getAPI().getSettings()
                .reEncodeByDefault(false) // Listeners must mark a packet as modified explicitly before it is re-encoded
                .checkForUpdates(false); // Server owners cannot swap out a bundled copy, so update notices would only mislead them
        PacketEvents.getAPI().load();
    }

    @Override
    public void onEnable() {
        PacketEvents.getAPI().init();
    }

    @Override
    public void onDisable() {
        PacketEvents.getAPI().terminate();
    }
}
