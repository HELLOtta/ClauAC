package io.github.hellotta.clauac;

import io.github.hellotta.clauac.bridge.SimulationBridge;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

// - /clauac debug: shows the result of every simulated tick in the player's action bar -
// - /clauac status: summarises the simulation of every connection -
final class ClauACCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("debug", "status");
    private final SimulationBridge bridge;

    ClauACCommand(SimulationBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            return false;
        }
        switch (args[0]) {
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
                return true;
            }
            case "status" -> {
                List<String> lines = this.bridge.status();
                if (lines.isEmpty()) {
                    sender.sendPlainMessage("No connections are watched.");
                }
                lines.forEach(sender::sendPlainMessage);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream().filter(subcommand -> subcommand.startsWith(args[0])).toList();
        }
        return List.of();
    }
}
