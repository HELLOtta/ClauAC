package io.github.hellotta.clauac.bridge;

import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

// - Sets players back on the server thread, where the server's own position of the player can be read. A tick whose -
// - movement never reached the server (see TickHold) only needs the client put back where the server still has the -
// - player: the connection teleports the client there with a packet of its own, keeping the client's rotation, and -
// - the server is not involved. When the player steers a vehicle, the connection puts the vehicle back where the -
// - server has it. -
// -
// - A tick whose movement reached the server before the simulation judged it (the simulation fell behind, or the -
// - connection started before the vanilla runtime) moved the player on the server too; the server itself then -
// - teleports the player back to where that tick began. Such a teleport would undo one of the server's own that -
// - came after the tick began, so it is left out then. It is left out as well for a tick that ended before an -
// - earlier one of these teleports: the client played that tick before it could take the teleport, which puts it -
// - back anyway, and the simulation began the tick where the tick before it had left the player, which the -
// - teleport undid. Vehicle movement that reached the server that way stays: the vehicle is only put back where the -
// - server has it -
public final class Setbacks {

    private final JavaPlugin plugin;
    private final Logger logger;
    // - When the server last put each player somewhere itself (teleports and respawns), System.nanoTime; server thread -
    private final Map<UUID, Long> repositions = new HashMap<>();
    // - When ClauAC last teleported each player back on the server (see teleportBack), System.nanoTime taken once the -
    // - teleport had gone out; server thread -
    private final Map<UUID, Long> teleportsBack = new HashMap<>();
    // - Set while ClauAC teleports a player, so that its own teleport does not count as one of the server's -
    private boolean teleporting;

    public Setbacks(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    // - From a connection's event loop. Returns false when the plugin can no longer run it -
    boolean request(ConnectionSimulation connection, SetbackRequest request) {
        try {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.execute(connection, request));
            return true;
        } catch (IllegalPluginAccessException exception) {
            return false;
        }
    }

    // - On the server thread -
    private void execute(ConnectionSimulation connection, SetbackRequest request) {
        String name = connection.user().getName();
        Player player = this.plugin.getServer().getPlayer(connection.user().getUUID());
        if (player == null || player.isDead() || player.isSleeping()) {
            this.skip(connection, request, name, "it is not moving in a level");
            return;
        }
        Entity vehicle = rootVehicle(player);
        if (vehicle != player) {
            if (!request.steered()) {
                // - The server puts a rider where its vehicle is -
                this.skip(connection, request, name, "it rides a vehicle it did not steer in that tick");
                return;
            }
            Location at = vehicle.getLocation();
            this.logger.info("Setting {} back after client tick {} ({}): its vehicle goes back to {} and stops", name, request.clientTick(), request.checks(), describe(at));
            connection.correctVehicle(request.generation(), vehicle.getEntityId(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
            return;
        }
        if (request.late()) {
            this.teleportBack(connection, request, player);
            return;
        }
        Location at = player.getLocation();
        ClientTickReport.Start start = request.start();
        // - The client goes on from the tick's start as if it had never moved in the failed tick, unless the server -
        // - has the player elsewhere by now -
        boolean atStart = at.getX() == start.x() && at.getY() == start.y() && at.getZ() == start.z();
        this.logger.info("Setting {} back after client tick {} ({}): to {}{}", name, request.clientTick(), request.checks(), describe(at),
                atStart ? "" : ", where the server has it, without its velocity");
        connection.correctPosition(request.generation(), at.getX(), at.getY(), at.getZ(),
                atStart ? start.velocityX() : 0.0, atStart ? start.velocityY() : 0.0, atStart ? start.velocityZ() : 0.0);
    }

    // - The tick's movement reached the server: the server teleports the player back to where the tick began, with -
    // - the player's rotation on the server -
    private void teleportBack(ConnectionSimulation connection, SetbackRequest request, Player player) {
        String name = player.getName();
        ClientTickReport.Start start = request.start();
        Long repositioned = this.repositions.get(player.getUniqueId());
        if (start.repositionPending() || repositioned != null && repositioned - request.endArrivalNanos() > 0L) {
            this.skip(connection, request, name, "the server put it somewhere itself after that tick began");
            return;
        }
        Long teleportedBack = this.teleportsBack.get(player.getUniqueId());
        if (teleportedBack != null && teleportedBack - request.endArrivalNanos() > 0L) {
            this.skip(connection, request, name, "an earlier setback teleported it back after that tick ended");
            return;
        }
        Location target = new Location(player.getWorld(), start.x(), start.y(), start.z(), player.getYaw(), player.getPitch());
        boolean teleported;
        this.teleporting = true;
        try {
            teleported = player.teleport(target, PlayerTeleportEvent.TeleportCause.UNKNOWN);
        } finally {
            this.teleporting = false;
        }
        if (!teleported) {
            this.logger.warn("Could not set {} back after client tick {} ({}): the server did not teleport it to {}; a plugin may have cancelled the teleport",
                    name, request.clientTick(), request.checks(), describe(target));
            connection.setbackSkipped(request.generation());
            return;
        }
        this.teleportsBack.put(player.getUniqueId(), System.nanoTime());
        this.logger.info("Setting {} back after client tick {} ({}): the server teleported it back to {}, since the tick's movement had reached the server",
                name, request.clientTick(), request.checks(), describe(target));
        connection.setbackTeleported(request.generation());
    }

    private void skip(ConnectionSimulation connection, SetbackRequest request, String name, String reason) {
        this.logger.info("Not setting {} back after client tick {} ({}): {}", name, request.clientTick(), request.checks(), reason);
        connection.setbackSkipped(request.generation());
    }

    // - The vehicle at the bottom of what the player rides, which the client steers when it steers anything; the -
    // - player itself when it rides nothing (Entity.getRootVehicle) -
    private static Entity rootVehicle(Player player) {
        Entity root = player;
        Entity below = root.getVehicle();
        while (below != null) {
            root = below;
            below = root.getVehicle();
        }
        return root;
    }

    private static String describe(Location location) {
        return String.format(Locale.ROOT, "%.4f %.4f %.4f", location.getX(), location.getY(), location.getZ());
    }

    // - On the server thread: the server put the player somewhere itself (a teleport or a respawn) -
    public void onReposition(UUID player) {
        if (!this.teleporting) {
            this.repositions.put(player, System.nanoTime());
        }
    }

    public void onQuit(UUID player) {
        this.repositions.remove(player);
        this.teleportsBack.remove(player);
    }
}
