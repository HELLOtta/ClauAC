package io.github.hellotta.clauac.simulation.player;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// - Port of the client-only RemotePlayer and the AbstractClientPlayer parts it uses: another player as the client -
// - shows it. It does not move by itself; the server's positions are interpolated, and it pushes the local player. -
// - Walk animation, bobbing, skins and render distance are left out -
public final class SandboxRemotePlayer extends Player {

    private final CachedPlayerInfo playerInfo;
    private Vec3 lerpDeltaMovement = Vec3.ZERO;
    private int lerpDeltaMovementSteps;

    public SandboxRemotePlayer(Level level, GameProfile gameProfile, PlayerInfoDirectory playerInfoDirectory) {
        super(level, gameProfile);
        this.playerInfo = new CachedPlayerInfo(playerInfoDirectory, gameProfile.id());
        this.noPhysics = true;
    }

    @Override
    public @Nullable GameType gameMode() {
        return this.playerInfo.gameMode();
    }

    // - Attacks on other players count as landed on the client, which applies the attacker's knockback slowdown -
    @Override
    public boolean hurtClient(DamageSource source) {
        return true;
    }

    @Override
    public void aiStep() {
        if (this.isInterpolating()) {
            this.getInterpolation().interpolate();
        }

        if (this.lerpHeadSteps > 0) {
            this.lerpHeadRotationStep(this.lerpHeadSteps, this.lerpYHeadRot);
            this.lerpHeadSteps--;
        }

        if (this.lerpDeltaMovementSteps > 0) {
            this.addDeltaMovement(
                    new Vec3(
                            (this.lerpDeltaMovement.x - this.getDeltaMovement().x) / this.lerpDeltaMovementSteps,
                            (this.lerpDeltaMovement.y - this.getDeltaMovement().y) / this.lerpDeltaMovementSteps,
                            (this.lerpDeltaMovement.z - this.getDeltaMovement().z) / this.lerpDeltaMovementSteps
                    )
            );
            this.lerpDeltaMovementSteps--;
        }

        this.updateSwingTime();
        this.pushEntities();
    }

    @Override
    public void lerpMotion(Vec3 movement) {
        this.lerpDeltaMovement = movement;
        this.lerpDeltaMovementSteps = this.getType().updateInterval() + 1;
    }

    @Override
    protected void updatePlayerPose() {
    }

    @Override
    public void recreateFromPacket(ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
        this.setOldPosAndRot();
    }
}
