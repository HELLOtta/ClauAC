package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.api.Check;
import io.github.hellotta.clauac.simulation.api.Flag;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import org.jspecify.annotations.Nullable;

// - What the client sent during one of its ticks, between two ServerboundClientTickEndPackets, plus the reasons -
// - found while processing packets why that tick cannot be verified -
final class ClientTickPackets {

    // - The movement packet LocalPlayer.sendPosition sent; it is sent at most once per tick -
    @Nullable ServerboundMovePlayerPacket movePacket;
    // - The vehicle position LocalPlayer.sendChanges sent while the player steers its vehicle -
    @Nullable ServerboundMoveVehiclePacket vehicleMove;
    // - What the client did with its keys and mouse during this tick (Minecraft.handleKeybinds and -
    // - MultiPlayerGameMode.tick), in order. These happen inside the tick, after the client processed the server's -
    // - packets and with the rotation the tick's movement packet reports, so they are replayed at the tick's end -
    final List<Packet<?>> actions = new ArrayList<>();
    // - A START_SPRINTING command was part of this tick -
    boolean sprintStartReported;
    // - A ServerboundPlayerAbilitiesPacket was part of this tick, and the flying state it carried -
    boolean abilitiesReported;
    boolean reportedFlying;
    // - A START_FALL_FLYING command was part of this tick -
    boolean fallFlyingStartReported;
    // - The jump power of a START_RIDING_JUMP command of this tick -
    OptionalInt reportedRidingJump = OptionalInt.empty();
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

    // - The note that lists what was rejected -
    static String rejectionNote(Iterable<Flag> rejections) {
        List<String> details = new ArrayList<>();
        for (Flag rejection : rejections) {
            details.add(rejection.detail());
        }
        return "rejected: " + String.join(", ", details);
    }

    void reset() {
        this.movePacket = null;
        this.vehicleMove = null;
        this.actions.clear();
        this.sprintStartReported = false;
        this.abilitiesReported = false;
        this.reportedFlying = false;
        this.fallFlyingStartReported = false;
        this.reportedRidingJump = OptionalInt.empty();
        this.predictedAbilitiesSent = false;
        this.predictedFallFlyingStart = false;
        this.predictedRidingJump = OptionalInt.empty();
        this.uncertainties.clear();
        this.alternatives.clear();
        this.rejections.clear();
        this.notes.clear();
    }
}
