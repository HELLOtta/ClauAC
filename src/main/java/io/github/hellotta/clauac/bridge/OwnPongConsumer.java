package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;

// - Takes the client's answers to ClauAC's own pings out of the connection (see ConnectionSimulation.consumeOwnPong). -
// - Registered with the priority that decides a packet's final state, so that every other plugin has seen the pong -
// - before it is cancelled, while SimulationBridge only observes -
public final class OwnPongConsumer implements PacketListener {

    private final SimulationBridge bridge;

    public OwnPongConsumer(SimulationBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PONG) {
            this.bridge.consumeOwnPong(event);
        }
    }
}
