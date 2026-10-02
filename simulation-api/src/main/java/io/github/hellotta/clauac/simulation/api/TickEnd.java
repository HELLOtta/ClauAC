package io.github.hellotta.clauac.simulation.api;

// - Where a client tick ends in its connection: how many serverbound packets were handed to the simulation up to and -
// - including the tick's end packet, counted from the connection's first, and when that end packet was handed in -
// - (System.nanoTime). The plugin counts the serverbound packets it hands in the same way, which tells it which of -
// - the client's packets belong to the tick -
public record TickEnd(long serverboundPackets, long arrivalNanos) {
}
