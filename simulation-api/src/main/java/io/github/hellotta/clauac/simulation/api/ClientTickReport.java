package io.github.hellotta.clauac.simulation.api;

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
        // - blocks or every 20 ticks); the reported fields then repeat the last reported position -
        boolean positionReported,
        double reportedX,
        double reportedY,
        double reportedZ,
        boolean reportedOnGround,
        boolean reportedHorizontalCollision,
        boolean reportedSprinting,
        // - Distance between the predicted and the reported position (the last reported one when the client sent -
        // - none this tick); NaN when the tick was not simulated -
        double offset,
        List<String> notes
) {

    public ClientTickReport {
        notes = List.copyOf(notes);
    }
}
