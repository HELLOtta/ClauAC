package io.github.hellotta.clauac.response;

import java.util.Map;

// - What happens to the packets of a failed tick: setBack throws the tick's movement away and puts the client back -
// - where the server has the player, and refusedActions are the tick's actions that failed a check, which never -
// - reach the server. Each is named by its place among the connection's serverbound packets (see Flag) and maps to -
// - the block prediction it carries, or Flag.NO_PREDICTION: ClauAC acknowledges the latest prediction of those it -
// - kept from the server in the server's stead, so that the client takes back what it predicted. A tick can call -
// - for both, when it fails a check of its movement and one of its actions -
public record TickResponse(boolean setBack, Map<Long, Integer> refusedActions) {

    // - Everything the tick sent goes on to the server -
    public static final TickResponse NONE = new TickResponse(false, Map.of());

    public TickResponse {
        refusedActions = Map.copyOf(refusedActions);
    }
}
