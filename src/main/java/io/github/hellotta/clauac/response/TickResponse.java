package io.github.hellotta.clauac.response;

// - What happens to the packets of a failed tick: setBack throws the tick's movement away and puts the client back -
// - where the server has the player, and dropActions keeps the tick's attacks and interactions with entities from -
// - the server. A tick can call for both, when it fails a check of its movement and one of its actions -
public record TickResponse(boolean setBack, boolean dropActions) {

    // - Everything the tick sent goes on to the server -
    public static final TickResponse NONE = new TickResponse(false, false);
}
