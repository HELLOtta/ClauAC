package io.github.hellotta.clauac.simulation.api;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// - Result of simulating one client tick. Positions are the player's feet position in world coordinates. A tick is -
// - MISMATCHED exactly when it failed a check, and its flags say which and why -
public record ClientTickReport(
        long clientTick,
        TickOutcome outcome,
        double predictedX,
        double predictedY,
        double predictedZ,
        boolean predictedOnGround,
        boolean predictedHorizontalCollision,
        boolean predictedSprinting,
        // - The player's velocity when the tick ended, as the simulation moved it, which the vanilla client's next -
        // - tick starts from (Entity.getDeltaMovement); NaN when the tick was not simulated -
        double predictedVelocityX,
        double predictedVelocityY,
        double predictedVelocityZ,
        // - false when the client sent no position this tick (it only does so after moving more than 2.0E-4 -
        // - blocks or every 20 ticks, and never while riding); the reported fields then repeat the last reported -
        // - position -
        boolean positionReported,
        double reportedX,
        double reportedY,
        double reportedZ,
        boolean reportedOnGround,
        boolean reportedHorizontalCollision,
        boolean reportedSprinting,
        // - Distance between the predicted and the reported position (the last reported one when the client sent -
        // - none this tick); NaN when the tick was not simulated or the player rode -
        double offset,
        // - The vehicle the player steered this tick; null when it steered none -
        VehicleState vehicle,
        // - The player when the tick began -
        Start start,
        // - The checks the tick failed; empty unless the outcome is MISMATCHED -
        List<Flag> flags,
        List<String> notes
) {

    // - The checks an uncertainty can explain: it leaves what the player and its vehicle did unknown, but never a -
    // - packet no vanilla client sends or anything else the tick failed -
    private static final Set<Check> MOVEMENT_CHECKS = EnumSet.of(Check.SIMULATION, Check.VEHICLE);

    // - The player when the tick began, before the client's actions and its movement: its position and velocity, NaN -
    // - when the client had no player, and whether a teleport, respawn or configuration of the server was still on -
    // - its way to the client then. Such a packet moves the player after this start, so that putting the player back -
    // - to the start would undo it -
    public record Start(double x, double y, double z, double velocityX, double velocityY, double velocityZ, boolean repositionPending) {

        // - A tick without a player -
        public static Start none(boolean repositionPending) {
            return new Start(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, repositionPending);
        }

        public boolean hasPosition() {
            return !Double.isNaN(this.x) && !Double.isNaN(this.y) && !Double.isNaN(this.z);
        }
    }

    // - A vehicle the player steers, as the simulation moved it and as the client reported it -
    // - (ServerboundMoveVehiclePacket). Positions are the vehicle's own position in world coordinates -
    public record VehicleState(
            // - The registered name of the vehicle's entity type -
            String type,
            double predictedX,
            double predictedY,
            double predictedZ,
            float predictedYRot,
            float predictedXRot,
            boolean predictedOnGround,
            // - The vehicle's velocity when the tick ended, as the simulation moved it (Entity.getDeltaMovement) -
            double predictedVelocityX,
            double predictedVelocityY,
            double predictedVelocityZ,
            // - false when the client sent no vehicle position this tick; the reported fields are then NaN and false -
            boolean positionReported,
            double reportedX,
            double reportedY,
            double reportedZ,
            float reportedYRot,
            float reportedXRot,
            boolean reportedOnGround,
            // - Distance between the predicted and the reported position; NaN when none was reported -
            double offset
    ) {

        // - Whether the simulation's end of the vehicle is known: its position, rotation and velocity -
        private boolean predictionKnown() {
            return allFinite(this.predictedX, this.predictedY, this.predictedZ, this.predictedYRot, this.predictedXRot,
                    this.predictedVelocityX, this.predictedVelocityY, this.predictedVelocityZ);
        }
    }

    public ClientTickReport {
        flags = List.copyOf(flags);
        notes = List.copyOf(notes);
        if (flags.isEmpty() == (outcome == TickOutcome.MISMATCHED)) {
            throw new IllegalArgumentException("a " + outcome + " tick with the flags " + flags);
        }
    }

    // - Whether the simulation moved the player in this tick as a vanilla client moves it with the same inputs, so -
    // - that the end of the tick is somewhere a vanilla client could be: the tick was simulated, where it ended is -
    // - known (position and velocity, and those of the vehicle the player steered), and it failed no check that puts -
    // - the inputs themselves in doubt (Check.doubtsInputs). A setback can then put the player there instead of back -
    // - where the tick began -
    public boolean predictionUsable() {
        if (this.flags.stream().anyMatch(flag -> flag.check().doubtsInputs())) {
            return false;
        }
        if (!allFinite(this.predictedX, this.predictedY, this.predictedZ, this.predictedVelocityX, this.predictedVelocityY, this.predictedVelocityZ)) {
            return false;
        }
        return this.vehicle == null || this.vehicle.predictionKnown();
    }

    private static boolean allFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    // - The same tick with a further note -
    public ClientTickReport withNote(String note) {
        return this.with(this.outcome, this.flags, note);
    }

    // - The same tick having failed one more check -
    public ClientTickReport withFlag(Flag flag, String note) {
        List<Flag> newFlags = new ArrayList<>(this.flags);
        newFlags.add(flag);
        return this.with(TickOutcome.MISMATCHED, newFlags, note);
    }

    // - The same tick with its movement left to an uncertainty found afterwards: the flags of the movement checks are -
    // - gone, and it is UNVERIFIED unless it failed another check -
    public ClientTickReport explainedBy(String note) {
        List<Flag> remaining = this.flags.stream().filter(flag -> !MOVEMENT_CHECKS.contains(flag.check())).toList();
        return this.with(remaining.isEmpty() ? TickOutcome.UNVERIFIED : TickOutcome.MISMATCHED, remaining, note);
    }

    private ClientTickReport with(TickOutcome newOutcome, List<Flag> newFlags, String note) {
        List<String> newNotes = new ArrayList<>(this.notes);
        newNotes.add(note);
        return new ClientTickReport(
                this.clientTick, newOutcome,
                this.predictedX, this.predictedY, this.predictedZ, this.predictedOnGround, this.predictedHorizontalCollision, this.predictedSprinting,
                this.predictedVelocityX, this.predictedVelocityY, this.predictedVelocityZ,
                this.positionReported, this.reportedX, this.reportedY, this.reportedZ, this.reportedOnGround, this.reportedHorizontalCollision, this.reportedSprinting,
                this.offset, this.vehicle, this.start, newFlags, newNotes
        );
    }
}
