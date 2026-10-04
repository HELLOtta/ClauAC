package io.github.hellotta.clauac.bridge;

import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import org.jspecify.annotations.Nullable;

// - A setback a connection asks the server thread for: which of the connection's setbacks it is, the client tick that -
// - failed and the checks it failed, whether the player steered a vehicle in that tick, where the tick began and when -
// - its end arrived, whether its movement reached the server before the simulation judged it, and where the -
// - simulation moved the player in it when the setback is to put the player there (setbacks.type predicted), null -
// - when it puts the player back where the server has it -
record SetbackRequest(
        int generation, long clientTick, String checks, boolean steered, ClientTickReport.Start start, long endArrivalNanos, boolean late,
        @Nullable PredictedEnd predicted
) {

    // - Where the simulation moved the player in the failed tick: the position and velocity a vanilla client with the -
    // - same keys and rotation would have ended the tick with, those of the vehicle while the player steered one, and -
    // - the vehicle's rotation, which the vehicle's correction sets (a correction of the player keeps the client's -
    // - own rotation, so it is NaN then) -
    record PredictedEnd(double x, double y, double z, double velocityX, double velocityY, double velocityZ, float yRot, float xRot) {

        // - The end of the player on foot -
        static PredictedEnd ofPlayer(ClientTickReport report) {
            return new PredictedEnd(report.predictedX(), report.predictedY(), report.predictedZ(),
                    report.predictedVelocityX(), report.predictedVelocityY(), report.predictedVelocityZ(), Float.NaN, Float.NaN);
        }

        // - The end of the vehicle the player steered -
        static PredictedEnd ofVehicle(ClientTickReport.VehicleState vehicle) {
            return new PredictedEnd(vehicle.predictedX(), vehicle.predictedY(), vehicle.predictedZ(),
                    vehicle.predictedVelocityX(), vehicle.predictedVelocityY(), vehicle.predictedVelocityZ(), vehicle.predictedYRot(), vehicle.predictedXRot());
        }
    }
}
