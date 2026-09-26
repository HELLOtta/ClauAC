package io.github.hellotta.clauac.simulation.session;

import com.mojang.authlib.GameProfile;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.PlayerSimulation;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import io.github.hellotta.clauac.simulation.api.SimulationListener;
import io.github.hellotta.clauac.simulation.registry.ConfigurationSession;
import io.github.hellotta.clauac.simulation.registry.ReceivedRegistries;
import io.github.hellotta.clauac.simulation.registry.ServerRegistryCache;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.common.ClientboundUpdateTagsPacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.configuration.ClientboundFinishConfigurationPacket;
import net.minecraft.network.protocol.configuration.ClientboundRegistryDataPacket;
import net.minecraft.network.protocol.configuration.ClientboundSelectKnownPacks;
import net.minecraft.network.protocol.configuration.ClientboundUpdateEnabledFeaturesPacket;
import net.minecraft.network.protocol.configuration.ServerboundSelectKnownPacks;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerRotationPacket;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.flag.FeatureFlagSet;
import org.jspecify.annotations.Nullable;

// - The simulated client of one connection. Clientbound packets wait in PendingClientbound until the client's own -
// - packets prove it processed them; serverbound packets tell what the client did in each of its ticks, and the -
// - client's tick end packet runs the simulated tick. Everything runs on the connection's SerialExecutor, in the -
// - network order the packets were handed in -
public final class ClientSession implements PlayerSimulation {

    private final GameProfile profile;
    private final SimulationListener listener;
    private final ServerRegistryCache registryCache;
    private final SerialExecutor executor;
    private final ProtocolDecoders decoders = new ProtocolDecoders();
    private final PendingClientbound pending = new PendingClientbound();
    // - Notes for the next tick report that arrive while no play connection exists -
    private final List<String> pendingNotes = new ArrayList<>();
    private volatile boolean closed;
    private boolean failed;
    private ReceivedRegistries registries;
    private FeatureFlagSet enabledFeatures;
    private @Nullable ConfigurationSession configuration;
    private @Nullable PlayConnection play;
    private long clientTick;

    public ClientSession(UUID profileId, String profileName, SimulationListener listener, ServerRegistryCache registryCache, Executor simulationThreads) {
        this.profile = new GameProfile(profileId, profileName);
        this.listener = listener;
        this.registryCache = registryCache;
        this.executor = new SerialExecutor(simulationThreads);
        this.registries = ConfigurationSession.initialRegistries();
        this.enabledFeatures = ConfigurationSession.initialFeatures();
        this.configuration = new ConfigurationSession(registryCache, this.registries.access(), this.enabledFeatures);
    }

    @Override
    public void handlePacket(ProtocolPhase phase, PacketDirection direction, byte[] encodedPacket) {
        if (!this.closed) {
            this.executor.execute(() -> this.process(phase, direction, encodedPacket));
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.executor.execute(this::release);
    }

    private void release() {
        this.pending.clear();
        this.pendingNotes.clear();
        this.configuration = null;
        this.play = null;
    }

    private void process(ProtocolPhase phase, PacketDirection direction, byte[] encodedPacket) {
        if (this.closed || this.failed) {
            return;
        }
        Packet<?> packet = null;
        try {
            packet = this.decoders.decode(phase, direction, encodedPacket);
            if (direction == PacketDirection.CLIENTBOUND) {
                this.onClientbound(phase, packet, encodedPacket);
            } else {
                this.onServerbound(phase, packet);
            }
        } catch (Throwable throwable) {
            String packetDescription = packet != null ? packet.type().toString() : phase + " " + direction + " packet id " + packetId(encodedPacket);
            this.fail("Could not process " + packetDescription + " at client tick " + this.clientTick, throwable);
            if (throwable instanceof Error error) {
                throw error;
            }
        }
    }

    private static String packetId(byte[] encodedPacket) {
        try {
            return Integer.toString(new FriendlyByteBuf(Unpooled.wrappedBuffer(encodedPacket)).readVarInt());
        } catch (RuntimeException exception) {
            return "unreadable (" + exception.getMessage() + ")";
        }
    }

    private void fail(String message, Throwable cause) {
        this.failed = true;
        this.release();
        this.listener.onSimulationFailure(message, cause);
    }

    private void note(String note) {
        if (this.play != null) {
            this.play.tickPackets().notes.add(note);
        } else {
            this.pendingNotes.add(note);
        }
    }

    private void onClientbound(ProtocolPhase phase, Packet<?> packet, byte[] encodedPacket) {
        this.pending.add(new PendingClientbound.PendingPacket(phase, packet, encodedPacket));
        if (phase == ProtocolPhase.CONFIGURATION) {
            this.applyLeadingConfigurationPackets();
        }
    }

    // - The client handles configuration packets as they arrive: it has no level, so there is no tick to line -
    // - them up with. They only wait behind play packets the client has not processed yet -
    private void applyLeadingConfigurationPackets() {
        for (PendingClientbound.PendingPacket released : this.pending.takeLeading(pending -> pending.phase() == ProtocolPhase.CONFIGURATION)) {
            this.applyConfiguration(released.packet(), released.encodedPacket());
        }
    }

    // - Applies everything the client provably processed, up to and including the answered packet -
    private void applyThrough(Predicate<Packet<?>> answeredPacket, String answer) {
        List<PendingClientbound.PendingPacket> released = this.pending.takeThrough(answeredPacket);
        if (released.isEmpty()) {
            this.note("the client sent " + answer + " for a packet the server never sent");
            return;
        }
        for (PendingClientbound.PendingPacket packet : released) {
            this.apply(packet);
        }
    }

    private void apply(PendingClientbound.PendingPacket released) {
        if (released.phase() == ProtocolPhase.CONFIGURATION) {
            this.applyConfiguration(released.packet(), released.encodedPacket());
        } else if (released.packet() instanceof ClientboundStartConfigurationPacket) {
            this.startConfiguration();
        } else {
            this.requirePlay().handle(released.packet(), released.encodedPacket());
        }
    }

    private PlayConnection requirePlay() {
        if (this.play == null) {
            throw new IllegalStateException("No play phase is active");
        }
        return this.play;
    }

    private ConfigurationSession requireConfiguration() {
        if (this.configuration == null) {
            throw new IllegalStateException("No configuration phase is active");
        }
        return this.configuration;
    }

    // - ClientPacketListener.handleConfigurationStart: the level and the player are dropped, and a new configuration -
    // - listener starts from the registries and features of the play phase that ends -
    private void startConfiguration() {
        this.play = null;
        this.configuration = new ConfigurationSession(this.registryCache, this.registries.access(), this.enabledFeatures);
    }

    private void applyConfiguration(Packet<?> packet, byte[] encodedPacket) {
        ConfigurationSession session = this.requireConfiguration();
        switch (packet) {
            case ClientboundRegistryDataPacket registryData -> session.handleRegistryData(registryData, encodedPacket);
            case ClientboundUpdateTagsPacket tags -> session.handleUpdateTags(tags, encodedPacket);
            case ClientboundUpdateEnabledFeaturesPacket features -> session.handleEnabledFeatures(features);
            case ClientboundSelectKnownPacks knownPacks -> session.handleServerKnownPacks(knownPacks);
            case ClientboundFinishConfigurationPacket ignored -> this.finishConfiguration(session);
            default -> throw new IllegalArgumentException("No handler for " + packet.type());
        }
    }

    // - ClientConfigurationPacketListenerImpl.handleConfigurationFinished -
    private void finishConfiguration(ConfigurationSession session) {
        this.registries = session.finish();
        this.enabledFeatures = session.enabledFeatures();
        this.configuration = null;
        this.decoders.bindPlay(this.registries.access());
        PlayConnection newPlay = new PlayConnection(this.profile, this.registries, this.enabledFeatures);
        newPlay.tickPackets().notes.addAll(this.pendingNotes);
        this.pendingNotes.clear();
        this.play = newPlay;
    }

    private void onServerbound(ProtocolPhase phase, Packet<?> packet) {
        if (phase == ProtocolPhase.CONFIGURATION) {
            if (packet instanceof ServerboundSelectKnownPacks knownPacks) {
                if (!this.requireConfiguration().handleClientKnownPacks(knownPacks)) {
                    this.note("the client selected known packs " + knownPacks.knownPacks() + " unlike a vanilla client");
                }
            } else {
                throw new IllegalArgumentException("No handler for " + packet.type());
            }
            return;
        }

        switch (packet) {
            case ServerboundPongPacket pong ->
                    this.applyThrough(pending -> pending instanceof ClientboundPingPacket ping && ping.getId() == pong.getId(), "pong " + pong.getId());
            case ServerboundAcceptTeleportationPacket accept -> {
                this.applyThrough(pending -> pending instanceof ClientboundPlayerPositionPacket position && position.id() == accept.id(),
                        "teleport acceptance " + accept.id());
                this.requirePlay().verifyTeleportAnswer(accept);
            }
            case ServerboundConfigurationAcknowledgedPacket ignored -> {
                this.applyThrough(pending -> pending instanceof ClientboundStartConfigurationPacket, "configuration acknowledgement");
                this.applyLeadingConfigurationPackets();
            }
            case ServerboundMovePlayerPacket.Rot rotation when this.isRotationAnswer(rotation) ->
                    this.applyThrough(pending -> pending instanceof ClientboundPlayerRotationPacket, "rotation answer");
            case ServerboundMovePlayerPacket move -> this.requirePlay().tickPackets().movePacket = move;
            case ServerboundPlayerInputPacket input -> this.requirePlay().onInputReported(input.input());
            case ServerboundPlayerCommandPacket command -> {
                switch (command.getAction()) {
                    case START_SPRINTING -> this.requirePlay().onSprintReported(true);
                    case STOP_SPRINTING -> this.requirePlay().onSprintReported(false);
                    case START_FALL_FLYING -> this.requirePlay().onFallFlyingStartReported();
                    default -> {
                        // - Sleeping, riding jumps and inventories do not change how the player moves on foot -
                    }
                }
            }
            case ServerboundPlayerAbilitiesPacket abilities -> this.requirePlay().onAbilitiesReported(abilities.isFlying());
            case ServerboundPlayerLoadedPacket ignored -> this.requirePlay().onPlayerLoaded();
            case ServerboundClientTickEndPacket ignored -> this.endClientTick();
            case ServerboundPlayerActionPacket action -> {
                PlayConnection connection = this.requirePlay();
                connection.onBlockPrediction(action.getSequence());
                switch (action.getAction()) {
                    case RELEASE_USE_ITEM -> connection.onItemReleased();
                    case STAB -> connection.tickPackets().uncertainties.add("attack");
                    default -> {
                        // - Breaking blocks is covered by the prediction sequence; dropping and swapping items does -
                        // - not change movement -
                    }
                }
            }
            case ServerboundUseItemOnPacket useItemOn -> this.requirePlay().onBlockPrediction(useItemOn.sequence());
            case ServerboundUseItemPacket useItem -> {
                PlayConnection connection = this.requirePlay();
                connection.onBlockPrediction(useItem.sequence());
                connection.onItemUseRequested();
            }
            case ServerboundAttackPacket ignored -> this.requirePlay().tickPackets().uncertainties.add("attack");
            case ServerboundInteractPacket ignored -> this.requirePlay().tickPackets().uncertainties.add("entity interaction");
            default -> throw new IllegalArgumentException("No handler for " + packet.type());
        }
    }

    // - The client answers a rotation packet with a rotation packet of its own right away; see -
    // - PlayConnection.isRotationAnswer. The rotation packet must be the next pending packet that changes the -
    // - player's rotation, because the client answers packets in order -
    private boolean isRotationAnswer(ServerboundMovePlayerPacket.Rot rotation) {
        PendingClientbound.PendingPacket rotationPacket = this.pending.findFirst(pending -> pending instanceof ClientboundPlayerRotationPacket);
        if (rotationPacket == null || this.play == null) {
            return false;
        }
        for (PendingClientbound.PendingPacket before : this.pending.peekBefore(rotationPacket)) {
            if (PlayConnection.replacesPlayerRotation(before.packet())) {
                return false;
            }
        }
        return this.play.isRotationAnswer(rotation, (ClientboundPlayerRotationPacket) rotationPacket.packet());
    }

    private void endClientTick() {
        PlayConnection connection = this.requirePlay();
        this.clientTick++;
        ClientTickReport report = connection.tick(this.clientTick);
        this.listener.onClientTick(report);
    }
}
