package io.github.hellotta.clauac.bridge;

import io.github.hellotta.clauac.simulation.api.ClientTickReport;

// - A setback a connection asks the server thread for: which of the connection's setbacks it is, the client tick that -
// - failed and the checks it failed, whether the player steered a vehicle in that tick, where the tick began and when -
// - its end arrived, and whether its movement reached the server before the simulation judged it -
record SetbackRequest(int generation, long clientTick, String checks, boolean steered, ClientTickReport.Start start, long endArrivalNanos, boolean late) {
}
