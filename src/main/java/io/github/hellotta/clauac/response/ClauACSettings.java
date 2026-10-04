package io.github.hellotta.clauac.response;

import io.github.hellotta.clauac.simulation.api.Check;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.slf4j.Logger;

// - ClauAC's settings from config.yml: who gets alerts, how often and how they look, where a setback puts the player -
// - and how long the client's packets may wait for their check, and which checks alert and set back. A check of the -
// - actions does not move the player: its setback keeps the action that failed it from the server (see -
// - Check.concernsActions). Read on the server thread when the plugin enables and on /clauac reload; the object never -
// - changes, so any thread may read it. The config.yml in the plugin jar holds every default: Bukkit falls back to -
// - it for a missing or unreadable value -
public record ClauACSettings(
        boolean alertsOnJoin, long alertIntervalNanos, String alertFormat, SetbackType setbackType, long maximumHoldNanos, Set<Check> alertedChecks,
        Set<Check> setbackChecks
) {

    private static final String ALERTS_ON_JOIN = "alerts.on-join";
    private static final String ALERT_INTERVAL_MILLIS = "alerts.interval-millis";
    private static final String ALERT_FORMAT = "alerts.format";
    private static final String SETBACK_TYPE = "setbacks.type";
    private static final String MAXIMUM_HOLD_MILLIS = "setbacks.maximum-hold-millis";
    private static final String CHECKS = "checks";
    private static final String CHECK_ALERT = ".alert";
    private static final String CHECK_SETBACK = ".setback";

    public ClauACSettings {
        alertedChecks = Set.copyOf(alertedChecks);
        setbackChecks = Set.copyOf(setbackChecks);
    }

    public static ClauACSettings load(Configuration config, Logger logger) {
        boolean alertsOnJoin = readBoolean(config, ALERTS_ON_JOIN, logger);
        long alertIntervalMillis = readNonNegativeLong(config, ALERT_INTERVAL_MILLIS, logger);
        String alertFormat = readString(config, ALERT_FORMAT, logger);
        SetbackType setbackType = readSetbackType(config, logger);
        long maximumHoldMillis = readNonNegativeLong(config, MAXIMUM_HOLD_MILLIS, logger);
        Set<Check> alertedChecks = EnumSet.noneOf(Check.class);
        Set<Check> setbackChecks = EnumSet.noneOf(Check.class);
        for (Check check : Check.values()) {
            String path = CHECKS + "." + check.displayName();
            if (readBoolean(config, path + CHECK_ALERT, logger)) {
                alertedChecks.add(check);
            }
            if (readBoolean(config, path + CHECK_SETBACK, logger)) {
                setbackChecks.add(check);
            }
        }
        ConfigurationSection checks = config.getConfigurationSection(CHECKS);
        if (checks != null) {
            for (String name : checks.getKeys(false)) {
                if (Arrays.stream(Check.values()).noneMatch(check -> check.displayName().equals(name))) {
                    logger.warn("Ignoring {}.{} in config.yml: ClauAC has no check of that name", CHECKS, name);
                }
            }
        }
        return new ClauACSettings(alertsOnJoin, TimeUnit.MILLISECONDS.toNanos(alertIntervalMillis), alertFormat, setbackType,
                TimeUnit.MILLISECONDS.toNanos(maximumHoldMillis), alertedChecks, setbackChecks);
    }

    // - The setback type of config.yml, or its default when the value names none -
    private static SetbackType readSetbackType(Configuration config, Logger logger) {
        String name = readString(config, SETBACK_TYPE, logger);
        Optional<SetbackType> type = SetbackType.byConfigName(name);
        if (type.isPresent()) {
            return type.get();
        }
        String defaultName = defaultString(config, SETBACK_TYPE);
        logger.warn("Ignoring {}: {} in config.yml, which is none of {}", SETBACK_TYPE, name, SetbackType.configNames());
        return SetbackType.byConfigName(defaultName)
                .orElseThrow(() -> new IllegalStateException("the config.yml in the plugin jar sets " + SETBACK_TYPE + " to " + defaultName + ", no setback type"));
    }

    private static boolean readBoolean(Configuration config, String path, Logger logger) {
        if (config.contains(path, true) && !config.isBoolean(path)) {
            logger.warn("Ignoring {}: {} in config.yml, which is neither true nor false", path, config.get(path));
            return defaults(config).getBoolean(path);
        }
        return config.getBoolean(path);
    }

    private static long readNonNegativeLong(Configuration config, String path, Logger logger) {
        if (config.contains(path, true) && !(config.isInt(path) || config.isLong(path)) || config.getLong(path) < 0L) {
            logger.warn("Ignoring {}: {} in config.yml, which is not a whole number from 0 up", path, config.get(path));
            return defaults(config).getLong(path);
        }
        return config.getLong(path);
    }

    private static String readString(Configuration config, String path, Logger logger) {
        if (config.contains(path, true) && !config.isString(path)) {
            logger.warn("Ignoring {}: {} in config.yml, which is not text", path, config.get(path));
            return defaultString(config, path);
        }
        // - getString(path) falls back to the defaults for a value the file does not have, as getBoolean(path) and -
        // - getLong(path) do; getString(path, def) gives def then (MemorySection.get(String, Object)) -
        String value = config.getString(path);
        return value != null ? value : defaultString(config, path);
    }

    // - The text the config.yml in the plugin jar has at this path -
    private static String defaultString(Configuration config, String path) {
        String value = defaults(config).getString(path);
        if (value == null) {
            throw new IllegalStateException("the config.yml in the plugin jar has no text at " + path);
        }
        return value;
    }

    // - The config.yml in the plugin jar, which JavaPlugin.getConfig sets as the defaults -
    private static Configuration defaults(Configuration config) {
        Configuration defaults = config.getDefaults();
        if (defaults == null) {
            throw new IllegalStateException("config.yml has no defaults; the plugin jar is missing its config.yml");
        }
        return defaults;
    }

    public boolean alerts(Check check) {
        return this.alertedChecks.contains(check);
    }

    public boolean setsBack(Check check) {
        return this.setbackChecks.contains(check);
    }
}
