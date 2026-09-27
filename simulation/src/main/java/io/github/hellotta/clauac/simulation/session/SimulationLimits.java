package io.github.hellotta.clauac.simulation.session;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

// - How far the simulation of one connection may fall behind the connection before it stops (see -
// - ClientSession.checkKeepingUp), and how much of the server's packets its client may leave unconfirmed before the -
// - older half is applied without its answer (see ClientSession.limitUnconfirmed). Read once from system properties -
// - when the runtime starts, for servers that need other limits and for testing them -
public record SimulationLimits(long maximumQueuedBytes, long maximumLagNanos, long maximumUnconfirmedBytes, int maximumUnconfirmedPackets) {

    static final long MEBIBYTE = 1024L * 1024L;
    // - A joining client receives a few megabytes of chunks at once, which the simulation works off within a second -
    // - or two; a simulation this far behind does not catch up, and its results come too late to help anyone -
    private static final long DEFAULT_MAXIMUM_QUEUED_MEBIBYTES = 64L;
    private static final long DEFAULT_MAXIMUM_LAG_MILLIS = TimeUnit.SECONDS.toMillis(30L);
    // - A vanilla client confirms the server's packets while it handles them; only a client that hangs or refuses to -
    // - answer gets anywhere near these -
    private static final long DEFAULT_MAXIMUM_UNCONFIRMED_MEBIBYTES = 32L;
    private static final long DEFAULT_MAXIMUM_UNCONFIRMED_PACKETS = 100_000L;

    public static SimulationLimits fromSystemProperties(Logger logger) {
        return new SimulationLimits(
                positive(logger, "clauac.maximumQueuedMebibytes", DEFAULT_MAXIMUM_QUEUED_MEBIBYTES, Long.MAX_VALUE / MEBIBYTE) * MEBIBYTE,
                TimeUnit.MILLISECONDS.toNanos(positive(logger, "clauac.maximumLagMillis", DEFAULT_MAXIMUM_LAG_MILLIS, Long.MAX_VALUE)),
                positive(logger, "clauac.maximumUnconfirmedMebibytes", DEFAULT_MAXIMUM_UNCONFIRMED_MEBIBYTES, Long.MAX_VALUE / MEBIBYTE) * MEBIBYTE,
                (int) positive(logger, "clauac.maximumUnconfirmedPackets", DEFAULT_MAXIMUM_UNCONFIRMED_PACKETS, Integer.MAX_VALUE)
        );
    }

    // - The whole number the property holds, or the default when it is not set or holds anything else than a whole -
    // - number from 1 to the maximum -
    private static long positive(Logger logger, String property, long defaultValue, long maximum) {
        String value = System.getProperty(property);
        if (value == null) {
            return defaultValue;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            logger.warn("Ignoring -D{}={}, which is not a whole number ({}); using {}", property, value, exception.getMessage(), defaultValue);
            return defaultValue;
        }
        if (parsed < 1L || parsed > maximum) {
            logger.warn("Ignoring -D{}={}, which is not from 1 to {}; using {}", property, value, maximum, defaultValue);
            return defaultValue;
        }
        return parsed;
    }

    public String describe() {
        return String.format(Locale.ROOT,
                "a connection's simulation stops once %d MiB of its packets or packets older than %d ms wait for it; "
                        + "a client that leaves more than %d MiB or %d of the server's packets unconfirmed gets the older half applied",
                this.maximumQueuedBytes / MEBIBYTE, TimeUnit.NANOSECONDS.toMillis(this.maximumLagNanos),
                this.maximumUnconfirmedBytes / MEBIBYTE, this.maximumUnconfirmedPackets);
    }
}
