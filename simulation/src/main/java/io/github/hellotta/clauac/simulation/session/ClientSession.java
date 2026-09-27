package io.github.hellotta.clauac.simulation.session;

import com.mojang.authlib.GameProfile;
import io.github.hellotta.clauac.simulation.api.Check;
import io.github.hellotta.clauac.simulation.api.ClientTickReport;
import io.github.hellotta.clauac.simulation.api.Flag;
import io.github.hellotta.clauac.simulation.api.PacketDirection;
import io.github.hellotta.clauac.simulation.api.PlayerSimulation;
import io.github.hellotta.clauac.simulation.api.ProtocolPhase;
import io.github.hellotta.clauac.simulation.api.SimulationListener;
import io.github.hellotta.clauac.simulation.api.SimulationStatistics;
import io.github.hellotta.clauac.simulation.api.TickEnd;
import io.github.hellotta.clauac.simulation.api.TickOutcome;
import io.github.hellotta.clauac.simulation.registry.ConfigurationSession;
import io.github.hellotta.clauac.simulation.registry.ReceivedRegistries;
import io.github.hellotta.clauac.simulation.registry.ServerRegistryCache;
import io.netty.buffer.Unpooled;
import java.io.Serial;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundContainerSlotStateChangedPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.flag.FeatureFlagSet;
import org.jspecify.annotations.Nullable;

// - The simulated client of one connection. Clientbound packets wait in PendingClientbound until the client's own -
// - packets prove it processed them; serverbound packets tell what the client did in each of its ticks, and the -
// - client's tick end packet runs the simulated tick. Everything runs on the connection's SerialExecutor, in the -
// - network order the packets were handed in -
public final class ClientSession implements PlayerSimulation {

    private static final double NANOS_PER_SECOND = 1.0E9;

    private final GameProfile profile;
    private final SimulationListener listener;
    private final ServerRegistryCache registryCache;
    private final SimulationLimits limits;
    private final SimulationCost cost = new SimulationCost();
    private final TickBudget tickBudget;
    private final SerialExecutor executor;
    private final ProtocolDecoders decoders = new ProtocolDecoders();
    private final PendingClientbound pending = new PendingClientbound();
    private final ProblemLog problemLog;
    // - Notes and rejections for the next tick report that arrive while no play connection exists -
    private final List<String> pendingNotes = new ArrayList<>();
    private final List<Flag> pendingRejections = new ArrayList<>();
    private volatile boolean closed;
    // - Set on the connection's event loop once the simulation fell too far behind (see checkKeepingUp) -
    private volatile boolean fellBehind;
    private boolean failed;
    private ReceivedRegistries registries;
    private FeatureFlagSet enabledFeatures;
    private @Nullable ConfigurationSession configuration;
    private @Nullable PlayConnection play;
    private long clientTick;
    // - The serverbound packets handed in so far, as TickEnd counts them -
    private long serverboundPackets;
    // - The tick whose report the play connection holds back, and that tick's time and end, which go with the report -
    private long heldTick = -1L;
    private long heldTickNanos;
    private @Nullable TickEnd heldTickEnd;

    public ClientSession(
            UUID profileId, String profileName, SimulationListener listener, ServerRegistryCache registryCache, Executor simulationThreads, SimulationLimits limits
    ) {
        this.profile = new GameProfile(profileId, profileName);
        this.listener = listener;
        this.problemLog = new ProblemLog(profileName);
        this.registryCache = registryCache;
        this.limits = limits;
        this.tickBudget = new TickBudget(limits.maximumTickBurstNanos());
        this.executor = new SerialExecutor(simulationThreads, this.cost);
        this.registries = ConfigurationSession.initialRegistries();
        this.enabledFeatures = ConfigurationSession.initialFeatures();
        this.configuration = new ConfigurationSession(registryCache, this.registries.access(), this.enabledFeatures);
    }

    // - The time a packet was handed in is the time it arrived, which the tick budget measures the client's ticks by -
    @Override
    public void handlePacket(ProtocolPhase phase, PacketDirection direction, byte[] encodedPacket) {
        if (this.closed || this.fellBehind) {
            return;
        }
        long arrivedAt = System.nanoTime();
        this.executor.execute(() -> this.process(phase, direction, encodedPacket, arrivedAt), encodedPacket.length);
        this.checkKeepingUp();
    }

    // - Stops the simulation once it fell too far behind the connection (see SimulationLimits), before the waiting -
    // - packets take the server's memory. The waiting packets are dropped, and the failure is reported after the task -
    // - that runs now, on the simulation thread like every other result -
    private void checkKeepingUp() {
        long queuedBytes = this.executor.queuedBytes();
        long lagNanos = this.executor.lagNanos(System.nanoTime());
        if (queuedBytes <= this.limits.maximumQueuedBytes() && lagNanos <= this.limits.maximumLagNanos()) {
            return;
        }
        this.fellBehind = true;
        String details = String.format(Locale.ROOT, "%d packets (%.1f MiB) waited for it, the oldest for %.3f s; the limits are %d MiB and %d ms",
                this.executor.queuedTasks(), (double) queuedBytes / SimulationLimits.MEBIBYTE, lagNanos / NANOS_PER_SECOND,
                this.limits.maximumQueuedBytes() / SimulationLimits.MEBIBYTE, TimeUnit.NANOSECONDS.toMillis(this.limits.maximumLagNanos()));
        this.executor.abandonWaitingTasks(() -> this.fail("The simulation fell behind the connection", new FellBehindException(details)));
    }

    @Override
    public SimulationStatistics statistics() {
        return this.cost.statistics(this.executor.queuedTasks(), this.executor.queuedBytes(), this.executor.lagNanos(System.nanoTime()),
                this.pending.size(), this.pending.bytes());
    }

    @Override
    public void close() {
        this.closed = true;
        this.executor.execute(this::release, 0L);
    }

    private void release() {
        this.endPlay();
        this.pending.clear();
        this.pendingNotes.clear();
        this.pendingRejections.clear();
        this.configuration = null;
    }

    // - Drops the play phase; a report it still held back is final now -
    private void endPlay() {
        PlayConnection ending = this.play;
        this.play = null;
        if (ending != null) {
            ClientTickReport held = ending.takeHeldReport("the play phase ended before the client reported the hotbar switch this tick assumed");
            if (held != null) {
                this.listener.onClientTick(held, this.heldTickNanos(held), this.heldTickEnd(held));
            }
        }
        this.heldTick = -1L;
        this.heldTickEnd = null;
    }

    // - The time of the tick whose report the play connection held back -
    private long heldTickNanos(ClientTickReport report) {
        this.requireHeld(report);
        return this.heldTickNanos;
    }

    // - Where the tick whose report the play connection held back ended -
    private TickEnd heldTickEnd(ClientTickReport report) {
        this.requireHeld(report);
        if (this.heldTickEnd == null) {
            throw new IllegalStateException("the end of held client tick " + report.clientTick() + " was not kept");
        }
        return this.heldTickEnd;
    }

    private void requireHeld(ClientTickReport report) {
        if (report.clientTick() != this.heldTick) {
            throw new IllegalStateException("the report of client tick " + report.clientTick() + " was not held back");
        }
    }

    // - A packet from the client that the sandbox cannot decode or apply is one no vanilla client sends in the -
    // - sandbox's situation: it is rejected, which makes its tick MISMATCHED, and the simulation goes on, so that such -
    // - packets cannot switch it off. A server packet the sandbox cannot apply would make the real client fail as -
    // - well, since the sandbox runs the client's own handlers; it stops the simulation -
    private void process(ProtocolPhase phase, PacketDirection direction, byte[] encodedPacket, long arrivedAt) {
        // - Every serverbound packet handed in counts, whether it can be decoded or not, as the plugin counts them -
        if (direction == PacketDirection.SERVERBOUND) {
            this.serverboundPackets++;
        }
        if (this.closed || this.failed) {
            return;
        }
        Packet<?> packet = null;
        try {
            packet = this.decoders.decode(phase, direction, encodedPacket);
            if (direction == PacketDirection.CLIENTBOUND) {
                this.onClientbound(phase, packet, encodedPacket);
            } else {
                this.onServerbound(phase, packet, arrivedAt);
            }
        } catch (Throwable throwable) {
            String packetDescription = packet != null ? packet.type().toString() : phase + " " + direction + " packet id " + packetId(encodedPacket);
            if (direction == PacketDirection.SERVERBOUND && throwable instanceof RuntimeException problem) {
                this.problemLog.log("rejected " + packetDescription + " after client tick " + this.clientTick, problem);
                this.reject(Check.BAD_PACKETS, packetDescription + " could not be applied (" + problem + ")");
                return;
            }
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

    // - A report the failed play phase still held back is delivered before the failure, in tick order. Releasing -
    // - must not hide the original problem, so a problem while releasing is attached to it -
    private void fail(String message, Throwable cause) {
        this.failed = true;
        try {
            this.release();
        } catch (RuntimeException releaseProblem) {
            cause.addSuppressed(releaseProblem);
        }
        this.listener.onSimulationFailure(message, cause);
    }

    private void note(String note) {
        if (this.play != null) {
            this.play.tickPackets().notes.add(note);
        } else {
            this.pendingNotes.add(note);
        }
    }

    // - Something the client sent that the sandbox rejected, with the check it fails; it makes the next tick report -
    // - MISMATCHED -
    private void reject(Check check, String rejection) {
        if (this.play != null) {
            this.play.tickPackets().reject(check, rejection);
        } else {
            this.pendingRejections.add(new Flag(check, rejection));
        }
    }

    private void onClientbound(ProtocolPhase phase, Packet<?> packet, byte[] encodedPacket) {
        this.pending.add(new PendingClientbound.PendingPacket(phase, packet, encodedPacket));
        if (phase == ProtocolPhase.CONFIGURATION) {
            this.applyLeadingConfigurationPackets();
        }
        this.limitUnconfirmed();
    }

    // - A vanilla client answers the ping behind a bundle while it handles the bundle, so the server's packets only -
    // - pile up unconfirmed while the client does not answer, whether it hangs or refuses to. Past the limits the -
    // - older half is applied as if the client had answered, as a vanilla client handles everything it received -
    // - before its next tick, and the next tick is MISMATCHED; the simulation goes on, so that holding back the -
    // - answers cannot switch it off -
    private void limitUnconfirmed() {
        long maximumBytes = this.limits.maximumUnconfirmedBytes();
        int maximumPackets = this.limits.maximumUnconfirmedPackets();
        if (this.pending.bytes() <= maximumBytes && this.pending.size() <= maximumPackets) {
            return;
        }
        for (PendingClientbound.PendingPacket released : this.pending.takeOldest(maximumBytes / 2, maximumPackets / 2)) {
            this.apply(released);
        }
        this.reject(Check.PINGS, String.format(Locale.ROOT, "the client left more than %d MiB or %d of the server's packets unconfirmed; the older half was applied without its answer",
                maximumBytes / SimulationLimits.MEBIBYTE, maximumPackets));
    }

    // - The client handles configuration packets as they arrive: it has no level, so there is no tick to line -
    // - them up with. They only wait behind play packets the client has not processed yet -
    private void applyLeadingConfigurationPackets() {
        for (PendingClientbound.PendingPacket released : this.pending.takeLeading(pending -> pending.phase() == ProtocolPhase.CONFIGURATION)) {
            this.applyConfiguration(released.packet(), released.encodedPacket());
        }
    }

    // - Applies everything the client provably processed, up to and including the answered packet. Returns false -
    // - when no pending packet is the answered one -
    private boolean applyThrough(Predicate<Packet<?>> answeredPacket, String answer) {
        List<PendingClientbound.PendingPacket> released = this.pending.takeThrough(answeredPacket);
        if (released.isEmpty()) {
            // - Every packet a client can answer reaches the sandbox, so a vanilla client never does this -
            this.reject(Check.BAD_PACKETS, "the client sent " + answer + " for a packet the server never sent");
            return false;
        }
        for (PendingClientbound.PendingPacket packet : released) {
            this.apply(packet);
        }
        return true;
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
        this.endPlay();
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
        PlayConnection newPlay = new PlayConnection(
                this.profile, this.registries, this.enabledFeatures, this.listener::onInventoryResyncNeeded, this.problemLog, this.cost);
        newPlay.tickPackets().notes.addAll(this.pendingNotes);
        newPlay.tickPackets().rejections.addAll(this.pendingRejections);
        this.pendingNotes.clear();
        this.pendingRejections.clear();
        this.play = newPlay;
    }

    private void onServerbound(ProtocolPhase phase, Packet<?> packet, long arrivedAt) {
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
                // - The answer to a teleport the server never sent tells nothing about where the client is -
                if (this.applyThrough(pending -> pending instanceof ClientboundPlayerPositionPacket position && position.id() == accept.id(),
                        "teleport acceptance " + accept.id())) {
                    this.requirePlay().verifyTeleportAnswer(accept);
                }
            }
            case ServerboundConfigurationAcknowledgedPacket ignored -> {
                this.applyThrough(pending -> pending instanceof ClientboundStartConfigurationPacket, "configuration acknowledgement");
                this.applyLeadingConfigurationPackets();
            }
            case ServerboundMovePlayerPacket.Rot rotation when this.isRotationAnswer(rotation) ->
                    this.applyThrough(pending -> pending instanceof ClientboundPlayerRotationPacket, "rotation answer");
            case ServerboundMovePlayerPacket move -> this.requirePlay().tickPackets().movePacket = move;
            case ServerboundMoveVehiclePacket vehicleMove -> this.requirePlay().tickPackets().vehicleMove = vehicleMove;
            case ServerboundPlayerInputPacket input -> this.requirePlay().onInputReported(input.input());
            case ServerboundPlayerCommandPacket command -> {
                switch (command.getAction()) {
                    case START_SPRINTING -> this.requirePlay().onSprintReported(true);
                    case STOP_SPRINTING -> this.requirePlay().onSprintReported(false);
                    case START_FALL_FLYING -> this.requirePlay().onFallFlyingStartReported();
                    // - The client jumps its vehicle itself and tells the server the power -
                    case START_RIDING_JUMP -> this.requirePlay().onRidingJumpReported(command.getData());
                    // - Only the enum and the server's handler know it; no code of the 26.3 client sends it -
                    case STOP_RIDING_JUMP -> this.reject(Check.BAD_PACKETS, "the client sent STOP_RIDING_JUMP, which a vanilla client never sends");
                    case STOP_SLEEPING, OPEN_INVENTORY -> {
                        // - Requests the server answers: leaving the bed and the mount's inventory screen only change -
                        // - the client once the server's packets for them arrive -
                    }
                }
            }
            case ServerboundPlayerAbilitiesPacket abilities -> this.requirePlay().onAbilitiesReported(abilities.isFlying());
            case ServerboundPlayerLoadedPacket ignored -> this.requirePlay().onPlayerLoaded();
            case ServerboundClientTickEndPacket ignored -> this.endClientTick(arrivedAt);
            // - What the client did during a tick with its keys and mouse; replayed at the tick's end -
            case ServerboundSetCarriedItemPacket _, ServerboundPlayerActionPacket _, ServerboundUseItemOnPacket _, ServerboundUseItemPacket _,
                 ServerboundAttackPacket _, ServerboundInteractPacket _, ServerboundPunchPacket _ ->
                    this.requirePlay().tickPackets().actions.add(packet);
            // - What the client did on a screen, between its ticks -
            case ServerboundContainerClickPacket click -> this.requirePlay().onContainerClick(click);
            case ServerboundContainerClosePacket close -> this.requirePlay().onContainerClose(close.getContainerId());
            case ServerboundSetCreativeModeSlotPacket creativeSlot -> this.requirePlay().onCreativeModeSlot(creativeSlot);
            case ServerboundContainerButtonClickPacket buttonClick -> this.requirePlay().onContainerButtonClick(buttonClick.containerId(), buttonClick.buttonId());
            case ServerboundSelectBundleItemPacket bundleItem -> this.requirePlay().onSelectBundleItem(bundleItem.slotId(), bundleItem.selectedItemIndex());
            case ServerboundSelectTradePacket trade -> this.requirePlay().onSelectTrade(trade.getItem());
            case ServerboundRenameItemPacket rename -> this.requirePlay().onRenameItem(rename.getName());
            case ServerboundContainerSlotStateChangedPacket slotState ->
                    this.requirePlay().onSlotStateChanged(slotState.slotId(), slotState.containerId(), slotState.newState());
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

    // - Every tick end counts against the tick budget; the ones outside the play phase are rejected anyway -
    private void endClientTick(long arrivedAt) {
        this.clientTick++;
        TickEnd end = new TickEnd(this.serverboundPackets, arrivedAt);
        // - The server's packets that will move the player but that the client had not processed when the tick began -
        boolean repositionPending = this.pending.findFirst(PlayConnection::repositionsPlayer) != null;
        PlayConnection connection = this.play;
        float millisPerTick = connection != null ? connection.clientTickMillis() : PlayConnection.DEFAULT_TICK_MILLIS;
        boolean inBudget = this.tickBudget.take(arrivedAt, millisPerTick);
        if (connection == null) {
            ClientTickReport report = this.tickOutsidePlay(repositionPending);
            this.listener.onClientTick(report, this.cost.endTick(System.nanoTime()), end);
            return;
        }
        List<ClientTickReport> reports = inBudget
                ? connection.tick(this.clientTick, repositionPending)
                : connection.skipTick(this.clientTick, new Flag(Check.TICK_RATE, String.format(Locale.ROOT,
                        "the client ended more ticks than real time allows, one per %.1f ms and at once those of %d ms, so this tick was not simulated",
                        millisPerTick, TimeUnit.NANOSECONDS.toMillis(this.limits.maximumTickBurstNanos()))), repositionPending);
        long tickNanos = this.cost.endTick(System.nanoTime());
        boolean delivered = false;
        for (ClientTickReport report : reports) {
            if (report.clientTick() == this.clientTick) {
                this.listener.onClientTick(report, tickNanos, end);
                delivered = true;
            } else {
                this.listener.onClientTick(report, this.heldTickNanos(report), this.heldTickEnd(report));
            }
        }
        if (delivered) {
            this.heldTick = -1L;
            this.heldTickEnd = null;
        } else {
            // - The play connection held this tick's report back -
            this.heldTick = this.clientTick;
            this.heldTickNanos = tickNanos;
            this.heldTickEnd = end;
        }
    }

    // - A vanilla client only ends its ticks while it is in a level; there is nothing to simulate or compare -
    private ClientTickReport tickOutsidePlay(boolean repositionPending) {
        List<Flag> rejections = new ArrayList<>(this.pendingRejections);
        rejections.add(new Flag(Check.BAD_PACKETS, "the client ended a tick outside the play phase, which a vanilla client never does"));
        List<String> notes = new ArrayList<>(this.pendingNotes);
        notes.add(ClientTickPackets.rejectionNote(rejections));
        this.pendingNotes.clear();
        this.pendingRejections.clear();
        return new ClientTickReport(
                this.clientTick, TickOutcome.MISMATCHED,
                Double.NaN, Double.NaN, Double.NaN, false, false, false,
                false, Double.NaN, Double.NaN, Double.NaN, false, false, false,
                Double.NaN, null, ClientTickReport.Start.none(repositionPending), rejections, notes
        );
    }

    // - Why the simulation stopped when it fell behind. The message tells everything, a stack trace would not; a -
    // - problem while releasing the simulation can still be attached (see fail) -
    private static final class FellBehindException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private FellBehindException(String message) {
            super(message, null, true, false);
        }
    }
}
