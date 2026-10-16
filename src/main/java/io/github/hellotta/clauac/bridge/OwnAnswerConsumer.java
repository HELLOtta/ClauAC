package io.github.hellotta.clauac.bridge;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;

// - Takes the client's answers to ClauAC's own pings and teleports out of the connection (see -
// - ConnectionSimulation.consumeOwnPong and consumeOwnTeleportAnswer). Registered with the priority that decides a -
// - packet's final state, so that every other plugin has seen the answer before it is cancelled, while -
// - SimulationBridge only observes -
public final class OwnAnswerConsumer implements PacketListener {

    private final SimulationBridge bridge;

    public OwnAnswerConsumer(SimulationBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PONG) {
            this.bridge.consumeOwnPong(event);
        } else if (event.getPacketType() == PacketType.Play.Client.TELEPORT_CONFIRM) {
            this.bridge.consumeOwnTeleportAnswer(event);
        }
    }
}
