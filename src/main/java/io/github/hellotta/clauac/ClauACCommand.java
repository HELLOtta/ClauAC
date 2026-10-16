package io.github.hellotta.clauac;

import io.github.hellotta.clauac.bridge.SimulationBridge;
import io.github.hellotta.clauac.response.Responses;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

// - /clauac debug: shows the result of every simulated tick in the player's action bar -
// - /clauac status: summarises the simulation of every connection -
// - /clauac alerts: turns the player's alerts about failed checks on or off -
// - /clauac reload: reads config.yml again -
final class ClauACCommand implements TabExecutor {

    private static final String ADMIN_PERMISSION = "clauac.admin";
    private static final List<String> SUBCOMMANDS = List.of("alerts", "debug", "reload", "status");
    private final SimulationBridge bridge;
    private final Responses responses;
    // - Reads config.yml again and returns its path -
    private final Supplier<String> reload;

    ClauACCommand(SimulationBridge bridge, Responses responses, Supplier<String> reload) {
        this.bridge = bridge;
        this.responses = responses;
        this.reload = reload;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return false;
        }
        String permission = permissionFor(args[0]);
        if (permission == null) {
            return false;
        }
        if (!sender.hasPermission(permission)) {
            sender.sendPlainMessage("You need the permission " + permission + " for /" + label + " " + args[0] + ".");
            return true;
        }
        switch (args[0]) {
            case "alerts" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendPlainMessage("Only players get alerts in their chat; the console logs every failed tick anyway.");
                    return true;
                }
                sender.sendPlainMessage("ClauAC alerts: " + (this.responses.toggleAlerts(player) ? "on" : "off"));
            }
            case "debug" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendPlainMessage("Only players have an action bar.");
                    return true;
                }
                Boolean enabled = this.bridge.toggleActionBar(player);
                if (enabled == null) {
                    sender.sendPlainMessage("Your connection is not simulated.");
                } else {
                    sender.sendPlainMessage("Simulation results in the action bar: " + (enabled ? "on" : "off"));
                }
            }
            case "reload" -> sender.sendPlainMessage("Read " + this.reload.get() + " again.");
            case "status" -> {
                List<String> lines = this.bridge.status();
                if (lines.isEmpty()) {
                    sender.sendPlainMessage("No connections are watched.");
                }
                lines.forEach(sender::sendPlainMessage);
            }
            default -> throw new IllegalStateException("permissionFor accepted /clauac " + args[0]);
        }
        return true;
    }

    // - The permission a subcommand needs; null for one that does not exist -
    private static @Nullable String permissionFor(String subcommand) {
        return switch (subcommand) {
            case "alerts" -> Responses.alertPermission();
            case "debug", "reload", "status" -> ADMIN_PERMISSION;
            default -> null;
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(subcommand -> subcommand.startsWith(args[0]) && sender.hasPermission(Objects.requireNonNull(permissionFor(subcommand))))
                    .toList();
        }
        return List.of();
    }
}
