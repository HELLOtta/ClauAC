package io.github.hellotta.clauac.response;

import io.github.hellotta.clauac.simulation.api.Check;
import io.github.hellotta.clauac.simulation.api.Flag;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

// - Tells the players who have alerts on when a player fails a check, at most once per player and check within the -
// - configured interval; the flags in between are counted into the next alert. Flags arrive on simulation threads, -
// - the messages go out on the server thread. They are sent as MiniMessage text through Paper's String API, so that -
// - no adventure object crosses into Paper (see the README's rule for ClauAC's own code) -
final class Alerts {

    static final String PERMISSION = "clauac.alerts";
    private static final Pattern PLACEHOLDER = Pattern.compile("%(player|check|detail|tick|count)%");

    private record ThrottleKey(UUID player, Check check) {
    }

    // - The last alert of one player and check, and the flags since, this one included; guarded by itself -
    private static final class Throttle {
        private boolean alerted;
        private long lastAlertNanos;
        private int pending;
    }

    private final JavaPlugin plugin;
    private final Logger logger;
    // - The players who have alerts on -
    private final Set<UUID> receivers = ConcurrentHashMap.newKeySet();
    private final Map<ThrottleKey, Throttle> throttles = new ConcurrentHashMap<>();

    Alerts(JavaPlugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    void flag(Player player, Flag flag, long clientTick, ClauACSettings settings) {
        int count = this.countIfDue(new ThrottleKey(player.getUniqueId(), flag.check()), settings.alertIntervalNanos());
        if (count == 0) {
            return;
        }
        String message = format(settings.alertFormat(), player.getName(), flag, clientTick, count);
        try {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.send(message));
        } catch (IllegalPluginAccessException exception) {
            // - A tick that finished while the plugin was being disabled -
            this.logger.warn("Dropped an alert that came after ClauAC was disabled: {}", message);
        }
    }

    // - The flags to report in an alert now, or 0 while the last alert of this player and check is more recent than -
    // - the interval -
    private int countIfDue(ThrottleKey key, long intervalNanos) {
        Throttle throttle = this.throttles.computeIfAbsent(key, ignored -> new Throttle());
        long now = System.nanoTime();
        synchronized (throttle) {
            throttle.pending++;
            if (throttle.alerted && now - throttle.lastAlertNanos < intervalNanos) {
                return 0;
            }
            int count = throttle.pending;
            throttle.pending = 0;
            throttle.alerted = true;
            throttle.lastAlertNanos = now;
            return count;
        }
    }

    // - Replaces the placeholders in one pass, so that a replaced value is never read as a placeholder, and escapes -
    // - the values so that MiniMessage shows them as they are -
    private static String format(String format, String playerName, Flag flag, long clientTick, int count) {
        Matcher matcher = PLACEHOLDER.matcher(format);
        StringBuilder message = new StringBuilder();
        while (matcher.find()) {
            String value = switch (matcher.group(1)) {
                case "player" -> playerName;
                case "check" -> flag.check().displayName();
                case "detail" -> flag.detail();
                case "tick" -> Long.toString(clientTick);
                case "count" -> Integer.toString(count);
                default -> throw new IllegalStateException("PLACEHOLDER matched " + matcher.group());
            };
            matcher.appendReplacement(message, Matcher.quoteReplacement(escapeMiniMessage(value)));
        }
        matcher.appendTail(message);
        return message.toString();
    }

    // - MiniMessage reads a backslash as escaping the character after it, and a '<' as the start of a tag -
    private static String escapeMiniMessage(String text) {
        return text.replace("\\", "\\\\").replace("<", "\\<");
    }

    // - On the server thread: to the players who have alerts on and still the permission -
    private void send(String message) {
        for (UUID receiverId : this.receivers) {
            Player receiver = this.plugin.getServer().getPlayer(receiverId);
            if (receiver != null && receiver.hasPermission(PERMISSION)) {
                receiver.sendRichMessage(message);
            }
        }
    }

    void enable(UUID player) {
        this.receivers.add(player);
    }

    // - Returns whether the player has alerts on now -
    boolean toggle(UUID player) {
        if (this.receivers.remove(player)) {
            return false;
        }
        this.receivers.add(player);
        return true;
    }

    // - A player left: it gets no alerts, and the alerts about it start over -
    void forget(UUID player) {
        this.receivers.remove(player);
        this.throttles.keySet().removeIf(key -> key.player().equals(player));
    }
}
