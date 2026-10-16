package io.github.hellotta.clauac.simulation.session;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;

// - Logs the problems the simulation of one connection runs into, with their stack traces. A client can repeat a -
// - rejected packet as often as it likes, so each distinct problem (exception type, message and the place it was -
// - thrown) is logged only the first time; every occurrence still appears in the report of its tick, and the server -
// - log shows the report of every MISMATCHED tick -
final class ProblemLog {

    private static final Logger LOGGER = LogUtils.getLogger();
    // - A client could make every problem look different; beyond this many, new ones only appear in the reports -
    private static final int MAXIMUM_DISTINCT_PROBLEMS = 64;

    private final String playerName;
    private final Set<String> loggedProblems = new HashSet<>();
    private boolean limitReached;

    ProblemLog(String playerName) {
        this.playerName = playerName;
    }

    // - what completes the sentence "The simulation of <player> ..." -
    void log(String what, Throwable problem) {
        StackTraceElement[] trace = problem.getStackTrace();
        String signature = problem.getClass().getName() + ": " + problem.getMessage() + " at " + (trace.length > 0 ? trace[0] : "an unknown place");
        if (this.loggedProblems.contains(signature)) {
            return;
        }
        if (this.loggedProblems.size() >= MAXIMUM_DISTINCT_PROBLEMS) {
            if (!this.limitReached) {
                this.limitReached = true;
                LOGGER.warn("The simulation of {} ran into more than {} distinct problems; further ones only appear in its reports",
                        this.playerName, MAXIMUM_DISTINCT_PROBLEMS);
            }
            return;
        }
        this.loggedProblems.add(signature);
        LOGGER.warn("The simulation of {} {}", this.playerName, what, problem);
    }
}
