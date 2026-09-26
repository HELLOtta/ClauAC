package io.github.hellotta.clauac.simulation.player;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;

// - Port of the client-only ClientInput and KeyboardInput. The client reads its keys every tick; the sandbox gets the -
// - same key state from the client's ServerboundPlayerInputPacket, which carries exactly the keys used in that tick. -
// - Auto jump is already folded into those keys: the client's makeJump() changes the key state before it is sent -
public final class SandboxInput {

    public Input keyPresses = Input.EMPTY;
    private Vec2 moveVector = Vec2.ZERO;

    private static float calculateImpulse(boolean positive, boolean negative) {
        if (positive == negative) {
            return 0.0F;
        }
        return positive ? 1.0F : -1.0F;
    }

    // - KeyboardInput.tick with the reported keys instead of the keyboard -
    public void tick(Input reportedKeys) {
        this.keyPresses = reportedKeys;
        float forwardImpulse = calculateImpulse(this.keyPresses.forward(), this.keyPresses.backward());
        float leftImpulse = calculateImpulse(this.keyPresses.left(), this.keyPresses.right());
        this.moveVector = new Vec2(leftImpulse, forwardImpulse).normalized();
    }

    public Vec2 getMoveVector() {
        return this.moveVector;
    }

    public boolean hasForwardImpulse() {
        return this.moveVector.y > 1.0E-5F;
    }
}
