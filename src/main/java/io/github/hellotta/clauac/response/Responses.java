package io.github.hellotta.clauac.response;

import io.github.hellotta.clauac.api.ClauACFlagEvent;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.Flag;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - What ClauAC does when a client tick fails checks: for every flag it calls ClauACFlagEvent and, unless a listener -
// - cancelled it, alerts and responds as the configuration says: a check of the movement sets the player back, and a -
// - check of the actions keeps the tick's attacks and interactions from the server. Ticks arrive on simulation -
// - threads -
public final class Responses {

    private final JavaPlugin plugin;
    private final Alerts alerts;
    private volatile ClauACSettings settings;
    private volatile boolean closed;

    public Responses(JavaPlugin plugin, Logger logger, ClauACSettings settings) {
        this.plugin = plugin;
        this.alerts = new Alerts(plugin, logger);
        this.settings = settings;
    }

    // - A tick of this player failed checks (report.flags() is not empty). Returns what happens to the tick's packets. -
    // - Before the server has the player in its world there is nobody to call the event for or to name in an alert; -
    // - the configuration alone then decides on the response -
    public TickResponse onFailedTick(@Nullable Player player, ClientTickReport report) {
        if (this.closed) {
            return TickResponse.NONE;
        }
        ClauACSettings current = this.settings;
        boolean setBack = false;
        boolean dropActions = false;
        for (Flag flag : report.flags()) {
            if (player != null && !this.call(new ClauACFlagEvent(player, flag.check(), flag.detail(), report.clientTick()))) {
                continue;
            }
            if (player != null && current.alerts(flag.check())) {
                this.alerts.flag(player, flag, report.clientTick(), current);
            }
            if (current.setsBack(flag.check())) {
                if (flag.check().concernsActions()) {
                    dropActions = true;
                } else {
                    setBack = true;
                }
            }
        }
        return new TickResponse(setBack, dropActions);
    }

    // - Calls the event with the plugin's class loader as the thread's context class loader: simulation threads -
    // - carry the vanilla runtime's, which other plugins' listeners must not run with. Returns whether no listener -
    // - cancelled it -
    private boolean call(ClauACFlagEvent event) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(this.plugin.getClass().getClassLoader());
        try {
            this.plugin.getServer().getPluginManager().callEvent(event);
        } finally {
            thread.setContextClassLoader(previous);
        }
        return !event.isCancelled();
    }

    // - The settings in effect; any thread -
    public ClauACSettings settings() {
        return this.settings;
    }

    // - On the server thread -
    public void reload(ClauACSettings newSettings) {
        this.settings = newSettings;
    }

    // - On the server thread, when a player joins -
    public void onJoin(Player player) {
        if (this.settings.alertsOnJoin() && player.hasPermission(Alerts.PERMISSION)) {
            this.alerts.enable(player.getUniqueId());
        }
    }

    public void onQuit(UUID player) {
        this.alerts.forget(player);
    }

    // - Returns whether the player has alerts on now -
    public boolean toggleAlerts(Player player) {
        return this.alerts.toggle(player.getUniqueId());
    }

    public static String alertPermission() {
        return Alerts.PERMISSION;
    }

    // - When the plugin disables: ticks that still finish are not responded to -
    public void close() {
        this.closed = true;
    }
}
