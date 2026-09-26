package io.github.hellotta.clauac.simulation.session;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.jspecify.annotations.Nullable;

// - What the client sent during one of its ticks, between two ServerboundClientTickEndPackets, plus the reasons -
// - found while processing packets why that tick cannot be verified -
final class ClientTickPackets {

    // - The movement packet LocalPlayer.sendPosition sent; it is sent at most once per tick -
    @Nullable ServerboundMovePlayerPacket movePacket;
    // - A START_SPRINTING command was part of this tick -
    boolean sprintStartReported;
    // - A ServerboundPlayerAbilitiesPacket was part of this tick, and the flying state it carried -
    boolean abilitiesReported;
    boolean reportedFlying;
    // - A START_FALL_FLYING command was part of this tick -
    boolean fallFlyingStartReported;
    // - What the simulated player itself would have sent while ticking -
    boolean predictedAbilitiesSent;
    boolean predictedFallFlyingStart;
    final Set<String> uncertainties = new LinkedHashSet<>();
    final List<String> notes = new ArrayList<>();

    void reset() {
        this.movePacket = null;
        this.sprintStartReported = false;
        this.abilitiesReported = false;
        this.reportedFlying = false;
        this.fallFlyingStartReported = false;
        this.predictedAbilitiesSent = false;
        this.predictedFallFlyingStart = false;
        this.uncertainties.clear();
        this.notes.clear();
    }
}
