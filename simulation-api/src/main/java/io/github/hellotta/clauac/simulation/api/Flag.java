package io.github.hellotta.clauac.simulation.api;

// - One reason a client tick is MISMATCHED: the check it failed and what exactly failed, in words. When a single -
// - packet of the client failed the check (an attack, an interaction, a block action, an item use), the flag names it: -
// - packet is its place among the connection's serverbound packets, counted as TickEnd counts them, so that the -
// - plugin can keep that packet from the server, and predictionSequence is the number of the block prediction it -
// - carries (MultiPlayerGameMode.startPrediction), which the client undoes once the server acknowledges it -
public record Flag(Check check, String detail, long packet, int predictionSequence) {

    // - The flag concerns the tick as a whole, or a packet that carries no block prediction -
    public static final long NO_PACKET = -1L;
    public static final int NO_PREDICTION = -1;

    public Flag(Check check, String detail) {
        this(check, detail, NO_PACKET, NO_PREDICTION);
    }

    public boolean hasPacket() {
        return this.packet != NO_PACKET;
    }

    public boolean hasPrediction() {
        return this.predictionSequence != NO_PREDICTION;
    }
}
