package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.api.Check;
import io.github.hellotta.clauac.simulation.api.Flag;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.InteractionHand;
import org.jspecify.annotations.Nullable;

// - What the client sent during one of its ticks, between two ServerboundClientTickEndPackets, plus the reasons -
// - found while processing packets why that tick cannot be verified -
final class ClientTickPackets {

    // - The movement packets the client sent since the tick began, oldest first, without those that answered a -
    // - packet of the server (see takePositionAnswer) -
    private final Deque<ServerboundMovePlayerPacket> playerMoves = new ArrayDeque<>();
    // - The movement packet LocalPlayer.sendPosition sent: the last of those; it is sent at most once per tick -
    @Nullable ServerboundMovePlayerPacket movePacket;
    // - The vehicle positions the client sent since the tick began, oldest first, without those that answered a -
    // - correction of the vehicle (see takeCorrectionAnswer) -
    private final Deque<ServerboundMoveVehiclePacket> vehicleMoves = new ArrayDeque<>();
    // - The vehicle position LocalPlayer.tick sent while the player steers its vehicle: the last of those -
    @Nullable ServerboundMoveVehiclePacket vehicleMove;
    // - What the client did with its keys and mouse during this tick (Minecraft.handleKeybinds and -
    // - MultiPlayerGameMode.tick), in order. These happen inside the tick, after the client processed the server's -
    // - packets and with the rotation the tick's movement packet reports, so they are replayed at the tick's end -
    final List<Packet<?>> actions = new ArrayList<>();
    // - Where each action came among the connection's serverbound packets, counted as TickEnd counts them (see Flag) -
    final List<Long> actionPackets = new ArrayList<>();
    // - A START_SPRINTING command was part of this tick -
    boolean sprintStartReported;
    // - A ServerboundPlayerAbilitiesPacket was part of this tick, and the flying state it carried -
    boolean abilitiesReported;
    boolean reportedFlying;
    // - A START_FALL_FLYING command was part of this tick -
    boolean fallFlyingStartReported;
    // - The jump power of a START_RIDING_JUMP command of this tick -
    OptionalInt reportedRidingJump = OptionalInt.empty();
    // - A packet of the server that the client processed since the tick before changed the player's rotation, or -
    // - replaced the player -
    boolean turnedByServer;
    // - What the simulated player itself would have sent while ticking -
    boolean predictedAbilitiesSent;
    boolean predictedFallFlyingStart;
    OptionalInt predictedRidingJump = OptionalInt.empty();
    final Set<String> uncertainties = new LinkedHashSet<>();
    // - What the client may have done instead where its packets leave gaps, tried at the player's tick when the -
    // - tick as simulated differs from what the client reported (see PlayConnection.tickLocalPlayer) -
    final List<TickAlternative> alternatives = new ArrayList<>();
    // - What the sandbox rejected during this tick: packets no vanilla client sends in the sandbox's situation, and -
    // - steps of the simulation that failed, each with the check it fails. Any of them makes the tick MISMATCHED, -
    // - whatever the comparison finds -
    final Set<Flag> rejections = new LinkedHashSet<>();
    final List<String> notes = new ArrayList<>();

    void reject(Check check, String detail) {
        this.rejections.add(new Flag(check, detail));
    }

    // - An action no vanilla client performs in the sandbox's situation, in the packet at this place, with the block -
    // - prediction that packet carries (Flag.NO_PREDICTION when none) -
    void rejectAction(Check check, String detail, long packet, int predictionSequence) {
        this.rejections.add(new Flag(check, detail, packet, predictionSequence));
    }

    void addAction(Packet<?> action, long packet) {
        this.actions.add(action);
        this.actionPackets.add(packet);
    }

    // - The client swings its own player when the server shows it a swing of it (ClientPacketListener.handleAnimate), -
    // - and LocalPlayer.swing sends that swing back right away, while the client handles the server's packets: before -
    // - it answers the ping behind the animation and before the ticks it runs afterwards, whose actions come after -
    // - that. The echo is therefore the oldest swing of that hand among the swings the tick's actions begin with. It -
    // - is taken out of the tick's actions, as no key of the client made it; false when the client sent none -
    boolean takeSwingEcho(InteractionHand hand) {
        for (int index = 0; index < this.actions.size() && this.actions.get(index) instanceof ServerboundSwingPacket swing; index++) {
            if (swing.getHand() == hand) {
                this.actions.remove(index);
                this.actionPackets.remove(index);
                return true;
            }
        }
        return false;
    }

    void addPlayerMove(ServerboundMovePlayerPacket move) {
        this.playerMoves.addLast(move);
        this.movePacket = move;
    }

    // - The client answers a teleport of its removed vehicle, which it applies to its player, right away with the -
    // - player's position (ClientPacketListener.handleTeleportEntity), while it handles the server's packets: before -
    // - it answers the ping behind the teleport and before the ticks it runs afterwards, each of which sends its own -
    // - movement after that. The answer is therefore the oldest movement packet of the tick that did not answer an -
    // - earlier packet. It is taken out of the tick's movement packets and returned; null when the client sent none -
    @Nullable ServerboundMovePlayerPacket takePositionAnswer() {
        ServerboundMovePlayerPacket answer = this.playerMoves.pollFirst();
        this.movePacket = this.playerMoves.peekLast();
        return answer;
    }

    void addVehicleMove(ServerboundMoveVehiclePacket move) {
        this.vehicleMoves.addLast(move);
        this.vehicleMove = move;
    }

    // - The client answers a correction of the vehicle it steers right away with the vehicle's position -
    // - (ClientPacketListener.handleMoveVehicle), while it handles the server's packets: before it answers the ping -
    // - behind the correction and before the ticks it runs afterwards, each of which sends its own position after -
    // - that. The answer is therefore the oldest vehicle position of the tick that did not answer an earlier -
    // - correction. It is taken out of the tick's positions and returned; null when the client sent none -
    @Nullable ServerboundMoveVehiclePacket takeCorrectionAnswer() {
        ServerboundMoveVehiclePacket answer = this.vehicleMoves.pollFirst();
        this.vehicleMove = this.vehicleMoves.peekLast();
        return answer;
    }

    // - The note that lists what was rejected -
    static String rejectionNote(Iterable<Flag> rejections) {
        List<String> details = new ArrayList<>();
        for (Flag rejection : rejections) {
            details.add(rejection.detail());
        }
        return "rejected: " + String.join(", ", details);
    }

    void reset() {
        this.playerMoves.clear();
        this.movePacket = null;
        this.vehicleMoves.clear();
        this.vehicleMove = null;
        this.actions.clear();
        this.actionPackets.clear();
        this.sprintStartReported = false;
        this.abilitiesReported = false;
        this.reportedFlying = false;
        this.fallFlyingStartReported = false;
        this.reportedRidingJump = OptionalInt.empty();
        this.turnedByServer = false;
        this.predictedAbilitiesSent = false;
        this.predictedFallFlyingStart = false;
        this.predictedRidingJump = OptionalInt.empty();
        this.uncertainties.clear();
        this.alternatives.clear();
        this.rejections.clear();
        this.notes.clear();
    }
}
