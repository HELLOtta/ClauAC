package io.github.hellotta.clauac.simulation.api;

import java.util.ArrayList;
import java.util.List;

// - Result of simulating one client tick. Positions are the player's feet position in world coordinates -
public record ClientTickReport(
        long clientTick,
        TickOutcome outcome,
        double predictedX,
        double predictedY,
        double predictedZ,
        boolean predictedOnGround,
        boolean predictedHorizontalCollision,
        boolean predictedSprinting,
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
        List<String> notes
) {

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
    }

    public ClientTickReport {
        notes = List.copyOf(notes);
    }

    // - The same tick with another outcome, and a note on why -
    public ClientTickReport withOutcome(TickOutcome newOutcome, String note) {
        List<String> newNotes = new ArrayList<>(this.notes);
        newNotes.add(note);
        return new ClientTickReport(
                this.clientTick, newOutcome,
                this.predictedX, this.predictedY, this.predictedZ, this.predictedOnGround, this.predictedHorizontalCollision, this.predictedSprinting,
                this.positionReported, this.reportedX, this.reportedY, this.reportedZ, this.reportedOnGround, this.reportedHorizontalCollision, this.reportedSprinting,
                this.offset, this.vehicle, newNotes
        );
    }
}
