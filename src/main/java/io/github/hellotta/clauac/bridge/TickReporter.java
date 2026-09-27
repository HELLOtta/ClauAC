package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.SimulationListener;
import io.github.hellotta.clauac.simulation.api.TickOutcome;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - Records the simulation results of one connection: every client tick as a CSV line, mismatches in the server log, -
// - and, while enabled, the latest result in the player's action bar -
final class TickReporter implements SimulationListener {

    private static final String CSV_HEADER = "clientTick,outcome,predictedX,predictedY,predictedZ,predictedOnGround,predictedHorizontalCollision,"
            + "predictedSprinting,positionReported,reportedX,reportedY,reportedZ,reportedOnGround,reportedHorizontalCollision,reportedSprinting,"
            + "offset,entityNearby,notes";

    private final User user;
    private final String playerName;
    private final Logger logger;
    private final Path csvFile;
    private final Map<TickOutcome, Long> outcomeCounts = new EnumMap<>(TickOutcome.class);
    private @Nullable BufferedWriter csv;
    private boolean closed;
    private double largestMismatch;
    private volatile boolean actionBarEnabled;
    private volatile boolean entityNearby;
    private final AtomicBoolean inventoryResyncRequested = new AtomicBoolean();
    // - Why the simulation stopped, once it has -
    private @Nullable String stopReason;

    TickReporter(User user, String playerName, Path csvFile, Logger logger) throws IOException {
        this.user = user;
        this.playerName = playerName;
        this.logger = logger;
        this.csvFile = csvFile;
        Files.createDirectories(csvFile.getParent());
        this.csv = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8);
        this.csv.write(CSV_HEADER);
        this.csv.newLine();
    }

    void setActionBarEnabled(boolean enabled) {
        this.actionBarEnabled = enabled;
    }

    boolean isActionBarEnabled() {
        return this.actionBarEnabled;
    }

    // - Set by the server thread every tick: whether another entity is close enough to push or carry the player. -
    // - Recorded with every tick, so that ticks around other entities can be told apart in the report -
    void setEntityNearby(boolean entityNearby) {
        this.entityNearby = entityNearby;
    }

    // - Taken by the server thread at the end of every tick, which then sends the player its inventory -
    boolean takeInventoryResyncRequest() {
        return this.inventoryResyncRequested.getAndSet(false);
    }

    @Override
    public void onInventoryResyncNeeded() {
        this.inventoryResyncRequested.set(true);
    }

    @Override
    public void onClientTick(ClientTickReport report) {
        boolean nearby = this.entityNearby;
        synchronized (this) {
            if (this.closed) {
                return;
            }
            this.outcomeCounts.merge(report.outcome(), 1L, Long::sum);
            if (report.outcome() == TickOutcome.MISMATCHED && report.offset() > this.largestMismatch) {
                this.largestMismatch = report.offset();
            }
            if (this.csv != null) {
                try {
                    this.csv.write(csvLine(report, nearby));
                    this.csv.newLine();
                } catch (IOException exception) {
                    this.logger.error("Could not write {}; no further ticks of {} are recorded", this.csvFile, this.playerName, exception);
                    this.closeCsv();
                }
            }
        }
        if (report.outcome() == TickOutcome.MISMATCHED) {
            this.logger.info("{} tick {} MISMATCHED by {}: predicted {} {} {} ground={} collision={} sprint={}, reported {} {} {} ground={} collision={} sprint={}{}; {}",
                    this.playerName, report.clientTick(), report.offset(),
                    report.predictedX(), report.predictedY(), report.predictedZ(), report.predictedOnGround(), report.predictedHorizontalCollision(), report.predictedSprinting(),
                    report.reportedX(), report.reportedY(), report.reportedZ(), report.reportedOnGround(), report.reportedHorizontalCollision(), report.reportedSprinting(),
                    nearby ? " (entity nearby)" : "", String.join("; ", report.notes()));
        }
        if (this.actionBarEnabled) {
            this.showInActionBar(report);
        }
    }

    private static String csvLine(ClientTickReport report, boolean entityNearby) {
        return String.join(",",
                Long.toString(report.clientTick()),
                report.outcome().name(),
                Double.toString(report.predictedX()),
                Double.toString(report.predictedY()),
                Double.toString(report.predictedZ()),
                Boolean.toString(report.predictedOnGround()),
                Boolean.toString(report.predictedHorizontalCollision()),
                Boolean.toString(report.predictedSprinting()),
                Boolean.toString(report.positionReported()),
                Double.toString(report.reportedX()),
                Double.toString(report.reportedY()),
                Double.toString(report.reportedZ()),
                Boolean.toString(report.reportedOnGround()),
                Boolean.toString(report.reportedHorizontalCollision()),
                Boolean.toString(report.reportedSprinting()),
                Double.toString(report.offset()),
                Boolean.toString(entityNearby),
                quote(String.join("; ", report.notes()))
        );
    }

    private static String quote(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private void showInActionBar(ClientTickReport report) {
        NamedTextColor color = switch (report.outcome()) {
            case MATCHED -> NamedTextColor.GREEN;
            case MISMATCHED -> NamedTextColor.RED;
            case UNVERIFIED -> NamedTextColor.YELLOW;
            case NOT_SIMULATED -> NamedTextColor.GRAY;
        };
        String offset = Double.isNaN(report.offset()) ? "-" : String.format(Locale.ROOT, "%.3e", report.offset());
        Component text = Component.text("ClauAC tick " + report.clientTick() + " " + report.outcome() + " offset " + offset, color);
        Object channel = this.user.getChannel();
        // - Only valid in the play phase; checked on the connection's event loop, where the phase changes -
        ChannelHelper.runInEventLoop(channel, () -> {
            if (ChannelHelper.isOpen(channel) && this.user.getEncoderState() == ConnectionState.PLAY) {
                this.user.sendPacket(new WrapperPlayServerActionBar(text));
            }
        });
    }

    @Override
    public void onSimulationFailure(String message, Throwable cause) {
        synchronized (this) {
            this.stopReason = message + ": " + cause;
        }
        this.logger.error("Simulation of {} stopped: {}", this.playerName, message, cause);
    }

    synchronized String summary() {
        long total = this.outcomeCounts.values().stream().mapToLong(Long::longValue).sum();
        String summary = String.format(Locale.ROOT, "%s: %d client ticks, matched %d, mismatched %d (largest offset %.6f), unverified %d, not simulated %d",
                this.playerName, total,
                this.outcomeCounts.getOrDefault(TickOutcome.MATCHED, 0L),
                this.outcomeCounts.getOrDefault(TickOutcome.MISMATCHED, 0L),
                this.largestMismatch,
                this.outcomeCounts.getOrDefault(TickOutcome.UNVERIFIED, 0L),
                this.outcomeCounts.getOrDefault(TickOutcome.NOT_SIMULATED, 0L));
        return this.stopReason != null ? summary + ", stopped (" + this.stopReason + ")" : summary;
    }

    synchronized void close() {
        if (!this.closed) {
            this.closed = true;
            this.closeCsv();
        }
    }

    private void closeCsv() {
        if (this.csv == null) {
            return;
        }
        try {
            this.csv.close();
        } catch (IOException exception) {
            this.logger.error("Could not close {}", this.csvFile, exception);
        }
        this.csv = null;
    }
}
