package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

// - Something the client may have done during a tick without its packets telling, as a change to the player right -
// - before the player's tick (see PlayConnection.tickLocalPlayer): the uncertainty it stands for while it cannot be -
// - tried, what it is when it matched, the item whose use it stops, which the client's next tick then has to -
// - confirm with a hotbar switch to another item (ItemStack.EMPTY for alternatives that need no confirmation), and -
// - the other entities whose motion the change sets, which the tick's start saves with the motion of the entities -
// - the player may push. An alternative of an action that failed a check stands for no uncertainty (null): what no -
// - vanilla client does must not leave the tick's movement unchecked when the alternative cannot be tried -
record TickAlternative(@Nullable String uncertainty, String description, ItemStack stoppedItem, Change change, List<Entity> pushedEntities) {

    TickAlternative(@Nullable String uncertainty, String description, ItemStack stoppedItem, Change change) {
        this(uncertainty, description, stoppedItem, change, List.of());
    }

    // - Applies the alternative to the player; it may restore a snapshot taken earlier in the tick -
    @FunctionalInterface
    interface Change {
        void apply(SandboxPlayer player) throws StateSnapshot.SnapshotException;
    }
}
