package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerActionBar;
import io.github.hellotta.clauac.response.Responses;
import io.github.hellotta.clauac.response.TickResponse;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.Flag;
import io.github.hellotta.clauac.simulation.api.SimulationListener;
import io.github.hellotta.clauac.simulation.api.SimulationStatistics;
import io.github.hellotta.clauac.simulation.api.TickEnd;
import io.github.hellotta.clauac.simulation.api.TickOutcome;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

// - Records the simulation results of one connection: every client tick as a CSV line, mismatches in the server log, -
// - and, while enabled, the latest result in the player's action bar. A failed tick also goes to the responses, and -
// - every tick's verdict goes to the connection, which lets the tick's packets on to the server -
final class TickReporter implements SimulationListener {

    private static final String CSV_HEADER = "clientTick,outcome,predictedX,predictedY,predictedZ,predictedOnGround,predictedHorizontalCollision,"
            + "predictedSprinting,positionReported,reportedX,reportedY,reportedZ,reportedOnGround,reportedHorizontalCollision,reportedSprinting,"
            + "offset,entityNearby,vehicle,vehiclePredictedX,vehiclePredictedY,vehiclePredictedZ,vehiclePredictedYRot,vehiclePredictedXRot,"
            + "vehiclePredictedOnGround,vehiclePositionReported,vehicleReportedX,vehicleReportedY,vehicleReportedZ,vehicleReportedYRot,"
            + "vehicleReportedXRot,vehicleReportedOnGround,vehicleOffset,simulationNanos,checks,notes";
    // - The vehicle columns of a tick without a steered vehicle -
    private static final String NO_VEHICLE_COLUMNS = ",".repeat(14);
    private static final double NANOS_PER_MILLISECOND = 1.0E6;
    private static final double NANOS_PER_SECOND = 1.0E9;
    private static final double BYTES_PER_KIBIBYTE = 1024.0;
    private static final double PERCENT = 100.0;

    private final User user;
    private final String playerName;
    private final Logger logger;
    private final Responses responses;
    private final ConnectionSimulation connection;
    // - Set by the server thread once the player is in the world -
    private volatile @Nullable Player player;
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

    TickReporter(User user, String playerName, Path csvFile, Logger logger, Responses responses, ConnectionSimulation connection) throws IOException {
        this.user = user;
        this.playerName = playerName;
        this.logger = logger;
        this.responses = responses;
        this.connection = connection;
        this.csvFile = csvFile;
        Files.createDirectories(csvFile.getParent());
        this.csv = Files.newBufferedWriter(csvFile, StandardCharsets.UTF_8);
        this.csv.write(CSV_HEADER);
        this.csv.newLine();
    }

    void setPlayer(Player player) {
        this.player = player;
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
    public void onClientTick(ClientTickReport report, long simulationNanos, TickEnd end) {
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
                    this.csv.write(csvLine(report, nearby, simulationNanos));
                    this.csv.newLine();
                } catch (IOException exception) {
                    this.logger.error("Could not write {}; no further ticks of {} are recorded", this.csvFile, this.playerName, exception);
                    this.closeCsv();
                }
            }
        }
        if (report.outcome() == TickOutcome.MISMATCHED) {
            this.logger.info("{} tick {} MISMATCHED ({}) by {}: predicted {} {} {} ground={} collision={} sprint={}, reported {} {} {} ground={} collision={} sprint={}{}{}; {}",
                    this.playerName, report.clientTick(), failedChecks(report), report.offset(),
                    report.predictedX(), report.predictedY(), report.predictedZ(), report.predictedOnGround(), report.predictedHorizontalCollision(), report.predictedSprinting(),
                    report.reportedX(), report.reportedY(), report.reportedZ(), report.reportedOnGround(), report.reportedHorizontalCollision(), report.reportedSprinting(),
                    nearby ? " (entity nearby)" : "", vehicleDescription(report.vehicle()), String.join("; ", report.notes()));
        }
        if (this.actionBarEnabled) {
            this.showInActionBar(report);
        }
        TickResponse response = report.outcome() == TickOutcome.MISMATCHED ? this.responses.onFailedTick(this.player, report) : TickResponse.NONE;
        this.connection.onVerdict(report, end, response);
    }

    private static String csvLine(ClientTickReport report, boolean entityNearby, long simulationNanos) {
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
                vehicleColumns(report.vehicle()),
                Long.toString(simulationNanos),
                quote(String.join("|", report.flags().stream().map(flag -> flag.check().displayName()).distinct().toList())),
                quote(String.join("; ", report.notes()))
        );
    }

    // - The checks a tick failed with what failed, as the log shows them -
    private static String failedChecks(ClientTickReport report) {
        List<String> failed = new ArrayList<>();
        for (Flag flag : report.flags()) {
            failed.add(flag.check().displayName() + ": " + flag.detail());
        }
        return String.join("; ", failed);
    }

    private static String vehicleColumns(ClientTickReport.@Nullable VehicleState vehicle) {
        if (vehicle == null) {
            return NO_VEHICLE_COLUMNS;
        }
        return String.join(",",
                quote(vehicle.type()),
                Double.toString(vehicle.predictedX()),
                Double.toString(vehicle.predictedY()),
                Double.toString(vehicle.predictedZ()),
                Float.toString(vehicle.predictedYRot()),
                Float.toString(vehicle.predictedXRot()),
                Boolean.toString(vehicle.predictedOnGround()),
                Boolean.toString(vehicle.positionReported()),
                Double.toString(vehicle.reportedX()),
                Double.toString(vehicle.reportedY()),
                Double.toString(vehicle.reportedZ()),
                Float.toString(vehicle.reportedYRot()),
                Float.toString(vehicle.reportedXRot()),
                Boolean.toString(vehicle.reportedOnGround()),
                Double.toString(vehicle.offset())
        );
    }

    private static String vehicleDescription(ClientTickReport.@Nullable VehicleState vehicle) {
        if (vehicle == null) {
            return "";
        }
        return String.format(Locale.ROOT, ", vehicle %s by %s: predicted %s %s %s rot %s %s ground=%s, reported %s %s %s rot %s %s ground=%s",
                vehicle.type(), vehicle.offset(),
                vehicle.predictedX(), vehicle.predictedY(), vehicle.predictedZ(), vehicle.predictedYRot(), vehicle.predictedXRot(), vehicle.predictedOnGround(),
                vehicle.reportedX(), vehicle.reportedY(), vehicle.reportedZ(), vehicle.reportedYRot(), vehicle.reportedXRot(), vehicle.reportedOnGround());
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
        // - While the player steers a vehicle, the vehicle's position is what the client reports -
        ClientTickReport.VehicleState vehicle = report.vehicle();
        double shownOffset = vehicle != null ? vehicle.offset() : report.offset();
        String offset = Double.isNaN(shownOffset) ? "-" : String.format(Locale.ROOT, "%.3e", shownOffset);
        String subject = vehicle != null ? " vehicle offset " : " offset ";
        String checks = report.flags().isEmpty() ? "" : " " + String.join(", ", report.flags().stream().map(flag -> flag.check().displayName()).distinct().toList());
        Component text = Component.text("ClauAC tick " + report.clientTick() + " " + report.outcome() + checks + subject + offset, color);
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
        this.connection.onSimulationStopped();
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

    // - What the simulation has cost, in one line: its share of a simulation thread, the time per client tick, the -
    // - packets waiting for it and how far it is behind, the server's packets the client has not confirmed, and the -
    // - snapshots of the player's state -
    String costSummary(SimulationStatistics statistics) {
        double threadShare = statistics.elapsedNanos() > 0L ? PERCENT * statistics.busyNanos() / statistics.elapsedNanos() : 0.0;
        double averageTick = statistics.ticks() > 0L ? statistics.busyNanos() / NANOS_PER_MILLISECOND / statistics.ticks() : 0.0;
        String recentTicks = statistics.recentTicks() > 0
                ? String.format(Locale.ROOT, "median %.2f ms and 99th percentile %.2f ms of the last %d",
                        statistics.recentMedianTickNanos() / NANOS_PER_MILLISECOND, statistics.recentPercentile99TickNanos() / NANOS_PER_MILLISECOND,
                        statistics.recentTicks())
                : "no tick yet";
        double averageSnapshot = statistics.snapshots() > 0L ? statistics.snapshotNanos() / NANOS_PER_MILLISECOND / statistics.snapshots() : 0.0;
        return String.format(Locale.ROOT,
                "%s: %.1f%% of a simulation thread, %.2f ms per client tick on average (%s, longest %.2f ms); "
                        + "%d packets (%.1f KiB) waiting, %.2f s behind (at most %.2f s); %d server packets (%.1f KiB) unconfirmed; "
                        + "%d snapshots of the player, %.3f ms each on average, up to %d objects",
                this.playerName, threadShare, averageTick, recentTicks, statistics.maxTickNanos() / NANOS_PER_MILLISECOND,
                statistics.queuedPackets(), statistics.queuedBytes() / BYTES_PER_KIBIBYTE, statistics.lagNanos() / NANOS_PER_SECOND,
                statistics.maxLagNanos() / NANOS_PER_SECOND, statistics.unconfirmedPackets(), statistics.unconfirmedBytes() / BYTES_PER_KIBIBYTE,
                statistics.snapshots(), averageSnapshot, statistics.maxSnapshotObjects());
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
