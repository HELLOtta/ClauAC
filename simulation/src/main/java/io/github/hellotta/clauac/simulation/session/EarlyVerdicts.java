package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.Flag;
import io.github.hellotta.clauac.simulation.api.SimulationListener;
import io.github.hellotta.clauac.simulation.api.TickEnd;
import io.github.hellotta.clauac.simulation.api.TickOutcome;
import org.jspecify.annotations.Nullable;

// - The verdicts one client tick has before its simulation ends, which go to the listener as soon as PlayConnection -
// - knows them (SimulationListener.onActionsPassed and onTickMatched), so that the plugin can let the tick's packets -
// - go on before the simulation of what the client ticks after its player: its actions once they all passed their -
// - checks, the whole tick once it matched. The tick's report has to show the same when it comes; contradiction -
// - tells what it does not -
final class EarlyVerdicts {

    // - Gives no verdict early: for a tick that is not simulated, and on a connection whose early verdicts are off -
    static final EarlyVerdicts NONE = new EarlyVerdicts(null, new TickEnd(0L, 0L), 0L);

    private final @Nullable SimulationListener listener;
    private final TickEnd end;
    private final long previousEnd;
    private boolean actionsPassed;
    private boolean tickMatched;

    EarlyVerdicts(@Nullable SimulationListener listener, TickEnd end, long previousEnd) {
        this.listener = listener;
        this.end = end;
        this.previousEnd = previousEnd;
    }

    // - Every action of the tick passed its checks, and none can fail any more -
    void actionsPassed() {
        if (this.listener == null || this.actionsPassed) {
            return;
        }
        this.actionsPassed = true;
        this.listener.onActionsPassed(this.end, this.previousEnd);
    }

    // - The tick matched, and nothing simulated after this point can change that -
    void tickMatched() {
        if (this.listener == null || this.tickMatched) {
            return;
        }
        this.tickMatched = true;
        this.listener.onTickMatched(this.end, this.previousEnd);
    }

    // - What the tick's report contradicts of the verdicts given early, or null when it shows the same -
    @Nullable String contradiction(ClientTickReport report) {
        if (this.tickMatched && (report.outcome() != TickOutcome.MATCHED || !report.flags().isEmpty())) {
            return "the tick was let go as matched, but its report is " + report.outcome() + " with " + report.flags().size() + " flags";
        }
        if (this.actionsPassed) {
            for (Flag flag : report.flags()) {
                if (flag.check().concernsActions()) {
                    return "the tick's actions were let go as passed, but its report has the flag " + flag.check().displayName() + " (" + flag.detail() + ")";
                }
            }
        }
        return null;
    }
}
