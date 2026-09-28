package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

// - Something the client may have done during a tick without its packets telling, as a change to the player right -
// - before the player's tick (see PlayConnection.tickLocalPlayer): the uncertainty it stands for while it cannot be -
// - tried, what it is when it matched, and the item whose use it stops, which the client's next tick then has to -
// - confirm with a hotbar switch to another item (ItemStack.EMPTY for alternatives that need no confirmation). An -
// - alternative of an action that failed a check stands for no uncertainty (null): what no vanilla client does must -
// - not leave the tick's movement unchecked when the alternative cannot be tried -
record TickAlternative(@Nullable String uncertainty, String description, ItemStack stoppedItem, Change change) {

    // - Applies the alternative to the player; it may restore a snapshot taken earlier in the tick -
    @FunctionalInterface
    interface Change {
        void apply(SandboxPlayer player) throws StateSnapshot.SnapshotException;
    }
}
