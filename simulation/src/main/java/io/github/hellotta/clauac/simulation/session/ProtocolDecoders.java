package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import org.jspecify.annotations.Nullable;

// - The protocols the client and the server use on one connection, decoding packets exactly like vanilla's -
// - PacketDecoder does. The play protocols are bound to the registries of the latest configuration, like the -
// - client binds them when it finishes a configuration phase -
final class ProtocolDecoders {

    private @Nullable ProtocolInfo<?> playClientbound;
    private @Nullable ProtocolInfo<?> playServerbound;

    // - ClientConfigurationPacketListenerImpl.handleConfigurationFinished binds both play protocols -
    void bindPlay(RegistryAccess registries) {
        this.playClientbound = GameProtocols.CLIENTBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries));
        // - The context the client encodes its packets with -
        this.playServerbound = GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(registries), new GameProtocols.Context() {
            @Override
            public boolean hasInfiniteMaterials() {
                return true;
            }
        });
    }

    Packet<?> decode(ProtocolPhase phase, PacketDirection direction, byte[] encodedPacket) {
        ProtocolInfo<?> protocol = this.protocol(phase, direction);
        ByteBuf input = Unpooled.wrappedBuffer(encodedPacket);
        Packet<?> packet = protocol.codec().decode(input);
        if (input.readableBytes() > 0) {
            throw new DecoderException("Packet " + protocol.id().id() + "/" + packet.type() + " was larger than expected, found "
                    + input.readableBytes() + " bytes extra");
        }
        return packet;
    }

    private ProtocolInfo<?> protocol(ProtocolPhase phase, PacketDirection direction) {
        return switch (phase) {
            case CONFIGURATION -> direction == PacketDirection.CLIENTBOUND ? ConfigurationProtocols.CLIENTBOUND : ConfigurationProtocols.SERVERBOUND;
            case PLAY -> {
                ProtocolInfo<?> play = direction == PacketDirection.CLIENTBOUND ? this.playClientbound : this.playServerbound;
                if (play == null) {
                    throw new IllegalStateException("Received a play packet before any configuration phase finished");
                }
                yield play;
            }
        };
    }
}
