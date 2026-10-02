package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import io.github.hellotta.clauac.simulation.world.SandboxBlockPredictions;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CrafterMenu;
import net.minecraft.world.inventory.CrafterSlot;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

// - Port of the client-only MultiPlayerGameMode: the local game mode and everything the client does on its own -
// - when the player breaks, uses, attacks, interacts, clicks in a menu or drops an item. The client predicts the -
// - results locally before the server answers, and those predictions change the player's world, inventory and -
// - movement, so the sandbox performs them too. The sandbox learns from the client's packets what the player did; -
// - what the client decided from its keys alone (how long mining took, whether a swing hit anything) is followed -
// - from the packets that decision produced -
final class SandboxGameMode {

    // - MultiPlayerGameMode sets destroyDelay to 5 after a finished or creative break -
    private static final int DESTROY_DELAY_TICKS = 5;
    private static final float PERCENT = 100.0F;

    private GameType localPlayerMode = GameType.DEFAULT_MODE;
    private MiningState mining = new MiningState();
    // - The last start of breaking made with an item the sandbox does not know while another hotbar item would have -
    // - left the client in another world (see UncertainStart), until the client's packets or the server tell which -
    // - world the client is in -
    private @Nullable UncertainStart uncertainStart;

    // - MultiPlayerGameMode's survival mining state, and whether destroyingItem is the item the client started -
    // - breaking with, which a start does not always tell (see startDestroyBlock) until the client goes on breaking -
    // - with the item it holds. Like the client, it keeps the item's own stack as destroyingItem -
    static final class MiningState {

        private boolean isDestroying;
        private BlockPos destroyBlockPos = new BlockPos(-1, -1, -1);
        private ItemStack destroyingItem = ItemStack.EMPTY;
        private float destroyProgress;
        private int destroyDelay;
        private boolean destroyingItemKnown = true;

        MiningState copy() {
            MiningState copy = new MiningState();
            copy.isDestroying = this.isDestroying;
            copy.destroyBlockPos = this.destroyBlockPos;
            copy.destroyingItem = this.destroyingItem;
            copy.destroyProgress = this.destroyProgress;
            copy.destroyDelay = this.destroyDelay;
            copy.destroyingItemKnown = this.destroyingItemKnown;
            return copy;
        }

        // - The state MultiPlayerGameMode.startDestroyBlock leaves when it starts breaking the block with this item -
        MiningState destroying(BlockPos pos, ItemStack item, boolean itemKnown) {
            MiningState destroying = this.copy();
            destroying.isDestroying = true;
            destroying.destroyBlockPos = pos;
            destroying.destroyingItem = item;
            destroying.destroyProgress = 0.0F;
            destroying.destroyingItemKnown = itemKnown;
            return destroying;
        }

        boolean sameAs(MiningState other) {
            return this.isDestroying == other.isDestroying && this.destroyBlockPos.equals(other.destroyBlockPos)
                    && ItemStack.isSameItemSameComponents(this.destroyingItem, other.destroyingItem) && this.destroyProgress == other.destroyProgress
                    && this.destroyDelay == other.destroyDelay && this.destroyingItemKnown == other.destroyingItemKnown;
        }
    }

    // - What a start of breaking does with an item: whether it breaks the block at once rather than start breaking -
    // - it (in creative mode it always does), and whether that break changes the block (destroyBlock) -
    private record StartOutcome(boolean breaksAtOnce, boolean changesBlock) {
    }

    // - A start's outcome with other hotbar items than the held one that differs from the held item's: one of those -
    // - items, and whether it is the only hotbar item with that outcome -
    private record OtherOutcome(int slot, ItemStack item, StartOutcome outcome, boolean onlyItem) {
    }

    GameType localPlayerMode() {
        return this.localPlayerMode;
    }

    // - MultiPlayerGameMode.isDestroying -
    boolean isDestroying() {
        return this.mining.isDestroying;
    }

    boolean isSpectator() {
        return this.localPlayerMode == GameType.SPECTATOR;
    }

    @Nullable UncertainStart uncertainStart() {
        return this.uncertainStart;
    }

    // - Puts another world's mining state in place, for as long as an action is checked in that world (see -
    // - PlayConnection); returns the one it replaced, which goes back the same way -
    MiningState swapMining(MiningState other) {
        MiningState replaced = this.mining;
        this.mining = other;
        return replaced;
    }

    // - The client was in this other world of the uncertain start, as its packets showed: the sandbox takes the -
    // - world's blocks, unless they are in place already, what the client keeps aside there and its mining state, and -
    // - notes the sky light source columns the world's break moved. The start is no longer uncertain -
    void adopt(SandboxLevel level, UncertainStart.OtherWorld world, boolean blocksInPlace) {
        if (!blocksInPlace) {
            level.putBlocks(world.blocks());
        }
        SandboxBlockPredictions predictions = level.getBlockPredictions();
        for (Map.Entry<BlockPos, SandboxBlockPredictions.RetainedState> retained : world.retained().entrySet()) {
            predictions.putRetained(retained.getKey(), retained.getValue());
        }
        for (BlockPos pos : world.retainedNothing()) {
            predictions.putRetained(pos, null);
        }
        for (BlockPos column : world.movedSkyLightColumns()) {
            level.noteSkyLightSourceMoved(column, world.skyLightMoveTick());
        }
        this.mining = world.mining().copy();
        this.uncertainStart = null;
    }

    // - The client's packets showed that it is in the sandbox's world -
    void forgetUncertainStart() {
        this.uncertainStart = null;
    }

    // - ClientboundBlockChangedAckPacket, before the level ends its predictions through this sequence: the client ends -
    // - those of the other worlds the same way (SandboxBlockPredictions.endPredictionsUpTo), and afterwards -
    // - dropConvergedWorlds finds the worlds that have become the sandbox's. A world that keeps nothing aside where the -
    // - sandbox ends a prediction goes on showing the block the sandbox shows now. Ending a prediction would put the -
    // - client's player back where it stood (SandboxLevel.syncBlockState) only where the server's block overlaps the -
    // - player, which the sandbox's world, in which the player moved as the client reported, rules out wherever the -
    // - sandbox has that block too; returns what the sandbox could not follow -
    List<String> onBlockChangedAck(SandboxLevel level, SandboxPlayer player, int sequence) {
        UncertainStart start = this.uncertainStart;
        if (start == null) {
            return List.of();
        }
        SandboxBlockPredictions predictions = level.getBlockPredictions();
        List<String> unfollowed = new ArrayList<>();
        for (UncertainStart.OtherWorld world : start.others()) {
            for (BlockPos pos : world.retainedNothing()) {
                if (!world.blocks().containsKey(pos)) {
                    world.blocks().put(pos, level.blockSnapshot(pos));
                }
            }
            Iterator<Map.Entry<BlockPos, SandboxBlockPredictions.RetainedState>> retained = world.retained().entrySet().iterator();
            while (retained.hasNext()) {
                Map.Entry<BlockPos, SandboxBlockPredictions.RetainedState> entry = retained.next();
                SandboxBlockPredictions.RetainedState kept = entry.getValue();
                if (kept.sequence() > sequence) {
                    continue;
                }
                BlockPos pos = entry.getKey();
                retained.remove();
                SandboxLevel.BlockSnapshot shown = world.blocks().get(pos);
                BlockState shownState = shown != null ? shown.state() : level.getBlockState(pos);
                if (shownState != kept.blockState()) {
                    if (player.isColliding(pos, kept.blockState())) {
                        unfollowed.add("the end of the prediction at " + pos.toShortString() + " put the client's player back, had it broken the block at "
                                + start.pos().toShortString() + " with " + PlayConnection.describeItem(world.item()) + ", which the sandbox cannot follow");
                    }
                    world.blocks().put(pos, new SandboxLevel.BlockSnapshot(kept.blockState(), null));
                }
                SandboxBlockPredictions.RetainedState levelKept = predictions.retained(pos);
                if (levelKept != null && levelKept.sequence() > sequence) {
                    world.retainedNothing().add(pos);
                }
            }
        }
        return unfollowed;
    }

    // - A block the server sends (ClientboundBlockUpdatePacket and the like), before the level takes it: every other -
    // - world keeps it aside where the client keeps a prediction there, and shows it otherwise -
    void onServerBlockState(SandboxLevel level, BlockPos pos, BlockState state) {
        UncertainStart start = this.uncertainStart;
        if (start == null) {
            return;
        }
        boolean levelKeepsAside = level.getBlockPredictions().retained(pos) != null;
        for (UncertainStart.OtherWorld world : start.others()) {
            SandboxBlockPredictions.RetainedState kept = world.retained().get(pos);
            if (kept != null) {
                world.retained().put(pos, new SandboxBlockPredictions.RetainedState(kept.sequence(), state, kept.playerPos()));
            } else if (world.retainedNothing().contains(pos) || !levelKeepsAside) {
                world.blocks().put(pos.immutable(), new SandboxLevel.BlockSnapshot(state, null));
            }
        }
    }

    // - After the level took the server's blocks: the positions where another world now has what the sandbox has drop -
    // - out of it, and a world left with nothing that differs is the sandbox's world -
    void dropConvergedWorlds(SandboxLevel level) {
        UncertainStart start = this.uncertainStart;
        if (start == null) {
            return;
        }
        SandboxBlockPredictions predictions = level.getBlockPredictions();
        for (UncertainStart.OtherWorld world : start.others()) {
            world.blocks().entrySet().removeIf(entry -> level.getBlockState(entry.getKey()) == entry.getValue().state());
            world.retained().entrySet().removeIf(entry -> entry.getValue().equals(predictions.retained(entry.getKey())));
            world.retainedNothing().removeIf(pos -> predictions.retained(pos) == null);
        }
        start.others().removeIf(world -> world.blocks().isEmpty() && world.retained().isEmpty() && world.retainedNothing().isEmpty()
                && world.mining().sameAs(this.mining));
        if (start.others().isEmpty()) {
            this.uncertainStart = null;
        }
    }

    // - handleLogin creates a new MultiPlayerGameMode, and the new level's predictions start over -
    void reset() {
        this.localPlayerMode = GameType.DEFAULT_MODE;
        this.mining = new MiningState();
        this.uncertainStart = null;
    }

    // - A new level: the blocks of the other worlds belonged to the old one -
    void onLevelChanged() {
        this.uncertainStart = null;
    }

    void adjustPlayer(SandboxPlayer player) {
        this.localPlayerMode.updatePlayerAbilities(player.getAbilities());
    }

    void setLocalMode(GameType mode, SandboxPlayer player) {
        this.localPlayerMode = mode;
        this.localPlayerMode.updatePlayerAbilities(player.getAbilities());
    }

    // - MultiPlayerGameMode.startPrediction: the client counts its predictions and sends the number with the -
    // - predicting packet -
    private static void predict(SandboxLevel level, int sequence, ClientTickPackets packets, Runnable prediction) {
        try (SandboxBlockPredictions predictions = level.getBlockPredictions()) {
            if (!predictions.startPredicting(sequence)) {
                packets.notes.add("the client's block prediction number " + sequence + " did not follow the sandbox's");
            }
            prediction.run();
        }
    }

    boolean destroyBlock(SandboxLevel level, SandboxPlayer player, BlockPos pos) {
        BlockState oldState = level.getBlockState(pos);
        if (!this.canDestroyBlock(level, player, pos, oldState)) {
            return false;
        }

        Block oldBlock = oldState.getBlock();
        oldBlock.playerWillDestroy(level, pos, oldState, player);
        FluidState fluidState = level.getFluidState(pos);
        boolean changed = level.setBlock(pos, fluidState.createLegacyBlock(), 11);
        if (changed) {
            oldBlock.destroy(level, pos, oldState);
        }

        return changed;
    }

    // - The checks MultiPlayerGameMode.destroyBlock makes, in its order, before it changes anything -
    private boolean canDestroyBlock(SandboxLevel level, SandboxPlayer player, BlockPos pos, BlockState state) {
        if (player.blockActionRestricted(level, pos, this.localPlayerMode)) {
            return false;
        }

        if (!player.getMainHandItem().canDestroyBlock(state, level, pos, player)) {
            return false;
        }

        if (state.getBlock() instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) {
            return false;
        }

        return !state.isAir();
    }

    // - START_DESTROY_BLOCK comes from MultiPlayerGameMode.startDestroyBlock, or from continueDestroyBlock in creative -
    // - mode, which predicts the same break. When the crosshair moved on while mining, the progress on the old block -
    // - is still set here and keeps the new block from being attacked, as on the client. Minecraft.startAttack sends -
    // - a start before the tick reports a hotbar key of the same key handling, as startDestroyBlock does not call -
    // - ensureHasSentCarriedItem; unless itemKnown, the client may have started with any hotbar item, which a later -
    // - tick reports, or no tick when the slot changed back before. Where another hotbar item would have done something -
    // - else, the start becomes the uncertain start with the worlds those items leave the client in (see -
    // - startWithEveryOutcome) -
    void startDestroyBlock(SandboxLevel level, SandboxPlayer player, BlockPos pos, int sequence, boolean itemKnown, ClientTickPackets packets) {
        if (player.getAbilities().instabuild) {
            BlockState state = level.getBlockState(pos);
            predict(level, sequence, packets, () -> this.startWithEveryOutcome(level, player, pos, state, sequence, itemKnown, packets,
                    () -> this.destroyBlock(level, player, pos)));
            this.mining.destroyDelay = DESTROY_DELAY_TICKS;
            // - The delay follows the start whatever the item did -
            UncertainStart start = this.uncertainStart;
            if (start != null && start.sequence() == sequence) {
                for (UncertainStart.OtherWorld world : start.others()) {
                    world.mining().destroyDelay = DESTROY_DELAY_TICKS;
                }
            }
            return;
        }
        BlockState state = level.getBlockState(pos);
        predict(level, sequence, packets, () -> {
            boolean notAir = !state.isAir();
            if (notAir && this.mining.destroyProgress == 0.0F) {
                state.attack(level, pos, player);
            }

            this.startWithEveryOutcome(level, player, pos, state, sequence, itemKnown, packets, () -> {
                if (notAir && state.getDestroyProgress(player, player.level(), pos) >= 1.0F) {
                    this.destroyBlock(level, player, pos);
                } else {
                    this.mining = this.mining.destroying(pos, player.getMainHandItem(), itemKnown || !this.anyOtherItemLikeHeld(level, player, pos, state));
                }
            });
        });
    }

    // - Runs the start with the held item (start) and, unless the item is known, finds out what it does in the -
    // - client's world with each hotbar item whose outcome differs (see StartOutcome): a break the held item does not -
    // - make is tried out beforehand with that item and taken back (see tryOtherBreak), and a break only the held item -
    // - makes is recorded, so that the world without it is known. Those worlds become the uncertain start, in place -
    // - of an earlier one, which the connection settles before every start (see PlayConnection) -
    private void startWithEveryOutcome(
            SandboxLevel level, SandboxPlayer player, BlockPos pos, BlockState state, int sequence, boolean itemKnown, ClientTickPackets packets, Runnable start
    ) {
        List<OtherOutcome> others = itemKnown ? List.of() : this.otherOutcomes(level, player, pos, state);
        if (others.isEmpty()) {
            start.run();
            return;
        }
        if (this.uncertainStart != null) {
            packets.notes.add("took the start of breaking at " + this.uncertainStart.pos().toShortString() + " for one with the held item");
            this.uncertainStart = null;
        }
        StartOutcome held = this.startOutcome(level, player, pos, state);
        MiningState before = this.mining.copy();
        List<UncertainStart.OtherWorld> worlds = new ArrayList<>();
        for (OtherOutcome other : others) {
            if (other.outcome().changesBlock() && !held.changesBlock()) {
                worlds.add(this.tryOtherBreak(level, player, pos, other, before, sequence));
            }
        }
        boolean recordHeld = held.changesBlock() && others.stream().anyMatch(other -> !other.outcome().changesBlock());
        SandboxBlockPredictions predictions = level.getBlockPredictions();
        Map<BlockPos, SandboxBlockPredictions.RetainedState> retainedBefore = recordHeld ? predictions.allRetained() : Map.of();
        Map<BlockPos, SandboxLevel.BlockSnapshot> heldBefore = Map.of();
        if (recordHeld) {
            level.recordBlockChanges(false);
        }
        try {
            start.run();
        } finally {
            if (recordHeld) {
                heldBefore = level.stopRecordingBlockChanges();
            }
        }
        for (OtherOutcome other : others) {
            MiningState mining = other.outcome().breaksAtOnce() ? before : before.destroying(pos, other.item(), other.onlyItem());
            if (other.outcome().changesBlock() && !held.changesBlock()) {
                continue;
            }
            Map<BlockPos, SandboxLevel.BlockSnapshot> blocks = new LinkedHashMap<>();
            Map<BlockPos, SandboxBlockPredictions.RetainedState> retained = new LinkedHashMap<>();
            List<BlockPos> retainedNothing = new ArrayList<>();
            if (held.changesBlock() && !other.outcome().changesBlock()) {
                for (Map.Entry<BlockPos, SandboxLevel.BlockSnapshot> changed : heldBefore.entrySet()) {
                    BlockPos changedPos = changed.getKey();
                    if (level.getBlockState(changedPos) == changed.getValue().state()) {
                        continue;
                    }
                    blocks.put(changedPos, changed.getValue());
                    SandboxBlockPredictions.RetainedState keptBefore = retainedBefore.get(changedPos);
                    if (keptBefore != null) {
                        retained.put(changedPos, keptBefore);
                    } else {
                        retainedNothing.add(changedPos);
                    }
                }
            }
            worlds.add(new UncertainStart.OtherWorld(other.item(), blocks, retained, retainedNothing, mining, List.of(), level.ticks()));
        }
        worlds.removeIf(world -> world.blocks().isEmpty() && world.retained().isEmpty() && world.retainedNothing().isEmpty()
                && world.mining().sameAs(this.mining));
        if (!worlds.isEmpty()) {
            this.uncertainStart = new UncertainStart(pos.immutable(), sequence, worlds);
        }
    }

    // - The world the start leaves the client in with an item that breaks the block where the held item does not: -
    // - the break tried out with that item and taken back (SandboxLevel.recordBlockChanges), with what the client -
    // - keeps aside for the blocks it changed as SandboxBlockPredictions.retainKnownServerState keeps it, the sky -
    // - light source columns it moved, and the mining state a break at once leaves as it was. The level's random is -
    // - put back as well, although no break on the client draws from it -
    private UncertainStart.OtherWorld tryOtherBreak(SandboxLevel level, SandboxPlayer player, BlockPos pos, OtherOutcome other, MiningState before, int sequence) {
        StateSnapshot random;
        try {
            random = StateSnapshot.capture(List.of(level.getRandom()));
        } catch (StateSnapshot.SnapshotException problem) {
            throw new IllegalStateException("the level's random could not be saved to try another hotbar item's break", problem);
        }
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        Map<BlockPos, SandboxLevel.BlockSnapshot> blocksBefore;
        level.recordBlockChanges(true);
        try {
            inventory.setSelectedSlot(other.slot());
            this.destroyBlock(level, player, pos);
        } finally {
            inventory.setSelectedSlot(selected);
            blocksBefore = level.stopRecordingBlockChanges();
        }
        Map<BlockPos, SandboxLevel.BlockSnapshot> blocks = new LinkedHashMap<>();
        Map<BlockPos, Integer> skyLightSources = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, SandboxLevel.BlockSnapshot> changed : blocksBefore.entrySet()) {
            BlockPos changedPos = changed.getKey();
            if (level.getBlockState(changedPos) != changed.getValue().state()) {
                blocks.put(changedPos, level.blockSnapshot(changedPos));
                skyLightSources.put(changedPos, level.lowestSkyLightSource(changedPos));
            }
        }
        level.putBlocks(blocksBefore);
        try {
            random.restore();
        } catch (StateSnapshot.SnapshotException problem) {
            throw new IllegalStateException("the level's random could not be put back after trying another hotbar item's break", problem);
        }
        List<BlockPos> movedSkyLightColumns = new ArrayList<>();
        for (Map.Entry<BlockPos, Integer> source : skyLightSources.entrySet()) {
            if (level.lowestSkyLightSource(source.getKey()) != source.getValue()) {
                movedSkyLightColumns.add(source.getKey());
            }
        }
        SandboxBlockPredictions predictions = level.getBlockPredictions();
        Map<BlockPos, SandboxBlockPredictions.RetainedState> retained = new LinkedHashMap<>();
        for (BlockPos changedPos : blocks.keySet()) {
            SandboxBlockPredictions.RetainedState kept = predictions.retained(changedPos);
            retained.put(changedPos, kept != null
                    ? new SandboxBlockPredictions.RetainedState(sequence, kept.blockState(), kept.playerPos())
                    : new SandboxBlockPredictions.RetainedState(sequence, blocksBefore.get(changedPos).state(), player.position()));
        }
        return new UncertainStart.OtherWorld(other.item(), blocks, retained, List.of(), before, movedSkyLightColumns, level.ticks());
    }

    // - The outcomes of the start with the hotbar items other than the held one that differ from the held item's, -
    // - with the items startDestroyBlock sends a start with: the game mode lets the player break the block with them -
    // - (Player.blockActionRestricted). Selecting a slot only sets the inventory's selected slot; the held slot is -
    // - selected again afterwards -
    private List<OtherOutcome> otherOutcomes(SandboxLevel level, SandboxPlayer player, BlockPos pos, BlockState state) {
        StartOutcome held = this.startOutcome(level, player, pos, state);
        Map<StartOutcome, OtherOutcome> found = new LinkedHashMap<>();
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        ItemStack heldItem = inventory.getItem(selected);
        try {
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (slot == selected || ItemStack.isSameItemSameComponents(item, heldItem)) {
                    continue;
                }
                inventory.setSelectedSlot(slot);
                if (player.blockActionRestricted(level, pos, this.localPlayerMode)) {
                    continue;
                }
                StartOutcome outcome = this.startOutcome(level, player, pos, state);
                if (!outcome.equals(held)) {
                    found.merge(outcome, new OtherOutcome(slot, item, outcome, true),
                            (first, next) -> new OtherOutcome(first.slot(), first.item(), first.outcome(), false));
                }
            }
        } finally {
            inventory.setSelectedSlot(selected);
        }
        return List.copyOf(found.values());
    }

    // - Whether another hotbar item than the held one starts breaking the block as the held item does, so that the -
    // - client may be breaking it with that one -
    private boolean anyOtherItemLikeHeld(SandboxLevel level, SandboxPlayer player, BlockPos pos, BlockState state) {
        StartOutcome held = this.startOutcome(level, player, pos, state);
        return anyOtherHotbarItem(player, () -> !player.blockActionRestricted(level, pos, this.localPlayerMode)
                && this.startOutcome(level, player, pos, state).equals(held));
    }

    // - The state is the one the block had when the start began; destroyBlock checks the block as it is by then -
    private StartOutcome startOutcome(SandboxLevel level, SandboxPlayer player, BlockPos pos, BlockState state) {
        boolean breaksAtOnce = player.getAbilities().instabuild || !state.isAir() && state.getDestroyProgress(player, player.level(), pos) >= 1.0F;
        return new StartOutcome(breaksAtOnce, breaksAtOnce && this.canDestroyBlock(level, player, pos, level.getBlockState(pos)));
    }

    // - Whether the test holds with any hotbar item other than the one held selected. Selecting a slot only sets the -
    // - inventory's selected slot; the held slot is selected again afterwards -
    private static boolean anyOtherHotbarItem(SandboxPlayer player, BooleanSupplier test) {
        Inventory inventory = player.getInventory();
        int selected = inventory.getSelectedSlot();
        ItemStack held = inventory.getItem(selected);
        try {
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                if (slot != selected && !ItemStack.isSameItemSameComponents(inventory.getItem(slot), held)) {
                    inventory.setSelectedSlot(slot);
                    if (test.getAsBoolean()) {
                        return true;
                    }
                }
            }
            return false;
        } finally {
            inventory.setSelectedSlot(selected);
        }
    }

    // - Whether any hotbar item the client may have held makes the test true: the one held when itemKnown, otherwise -
    // - any of them (see startDestroyBlock) -
    static boolean anyPossibleItem(SandboxPlayer player, boolean itemKnown, BooleanSupplier test) {
        return test.getAsBoolean() || !itemKnown && anyOtherHotbarItem(player, test);
    }

    // - STOP_DESTROY_BLOCK: continueDestroyBlock found the block broken (see whyNoFinish). The client was breaking this -
    // - block with the item it holds -
    void finishDestroyBlock(SandboxLevel level, SandboxPlayer player, BlockPos pos, int sequence, ClientTickPackets packets) {
        this.mining.destroyBlockPos = pos;
        this.mining.destroyingItem = player.getMainHandItem();
        this.mining.destroyingItemKnown = true;
        this.mining.isDestroying = false;
        predict(level, sequence, packets, () -> this.destroyBlock(level, player, pos));
        this.mining.destroyProgress = 0.0F;
        this.mining.destroyDelay = DESTROY_DELAY_TICKS;
    }

    // - ABORT_DESTROY_BLOCK: either stopDestroyBlock (key released or crosshair on nothing) or the start of mining -
    // - another block, which the following START_DESTROY_BLOCK tells -
    void abortDestroyBlock(SandboxPlayer player, boolean switchingTarget) {
        if (switchingTarget) {
            return;
        }
        if (this.mining.isDestroying) {
            this.mining.isDestroying = false;
            this.mining.destroyProgress = 0.0F;
            player.resetAttackStrengthTicker();
        }
    }

    // - A swing (ServerboundPunchPacket) that no attack or block packet accompanied. Minecraft.startAttack sends one -
    // - after every click that started nothing: on nothing (or air) it resets the attack strength ticker, on an -
    // - entity out of a weapon's attack range or on a block it cannot or need not start breaking it does nothing. -
    // - Minecraft.continueAttack sends one after continueDestroyBlock went on with the block the crosshair points at, -
    // - once per tick and after everything else the key handling sends, so only a swing that ends the tick's actions -
    // - can be that one (continuesBreaking): it counts down the delay after a break, or adds the tick's share of the -
    // - breaking progress when that block is the one being broken with the same item, which shows the client started -
    // - with the item it holds. What the client's crosshair pointed at is computed the way Minecraft.pick does -
    void swingAlone(SandboxLevel level, SandboxPlayer player, HitResult hitResult, boolean continuesBreaking) {
        if (hitResult instanceof BlockHitResult blockHit && hitResult.getType() == HitResult.Type.BLOCK && !level.getBlockState(blockHit.getBlockPos()).isAir()) {
            if (!continuesBreaking) {
                return;
            }
            // - continueDestroyBlock -
            if (this.mining.destroyDelay > 0) {
                this.mining.destroyDelay--;
            } else if (this.sameDestroyTarget(player, blockHit.getBlockPos())) {
                BlockState state = level.getBlockState(blockHit.getBlockPos());
                this.mining.destroyProgress = this.mining.destroyProgress + state.getDestroyProgress(player, player.level(), blockHit.getBlockPos());
                this.mining.destroyingItem = player.getMainHandItem();
                this.mining.destroyingItemKnown = true;
            }
        } else if (hitResult.getType() != HitResult.Type.ENTITY) {
            // - startAttack on nothing; an entity out of a weapon's attack range is not attacked and not reset -
            player.resetAttackStrengthTicker();
        }
    }

    // - Whether Minecraft.continueAttack would have sent a swing alone for the block the crosshair points at: -
    // - continueDestroyBlock counts the delay after a break down, or goes on with the block being broken, which it -
    // - finishes with a packet of its own once the progress reaches the whole block, as it starts every other block -
    // - with one, and breaks every block with a start in creative mode inside the world border. Without a block under -
    // - the crosshair, continueAttack sends nothing -
    boolean continuesAlone(SandboxLevel level, SandboxPlayer player, HitResult hitResult) {
        if (!(hitResult instanceof BlockHitResult blockHit) || hitResult.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos pos = blockHit.getBlockPos();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        if (this.mining.destroyDelay > 0) {
            return true;
        }
        if (player.getAbilities().instabuild && level.getWorldBorder().isWithinBounds(pos) || !this.sameDestroyTarget(player, pos)) {
            return false;
        }
        return this.mining.destroyProgress + state.getDestroyProgress(player, player.level(), pos) < 1.0F;
    }

    // - Whether MultiPlayerGameMode.startDestroyBlock would send a start for the block: never outside the world border, -
    // - always in creative mode, and otherwise unless it breaks that block with the same item already -
    boolean wouldStart(SandboxLevel level, SandboxPlayer player, BlockPos pos) {
        return level.getWorldBorder().isWithinBounds(pos)
                && (player.getAbilities().instabuild || !this.mining.isDestroying || !this.sameDestroyTarget(player, pos));
    }

    // - Whether a start of breaking the block fits the mining state: startDestroyBlock sends one only where it would -
    // - (wouldStart), and outside creative mode aborts the block it breaks with a packet of its own right before -
    // - (abortedFirst) -
    boolean startFits(SandboxLevel level, SandboxPlayer player, BlockPos pos, boolean abortedFirst) {
        return this.wouldStart(level, player, pos) && (player.getAbilities().instabuild || !this.mining.isDestroying || abortedFirst);
    }

    // - A teleport ends the client's mining in the other worlds too (MultiPlayerGameMode.stopDestroyBlock, as -
    // - abortDestroyBlock does). That also resets the attack strength ticker, which the sandbox cannot do in one world -
    // - only; returns whether some other world was breaking a block where the sandbox's was not (sandboxWasDestroying), -
    // - or the other way round -
    boolean abortInOtherWorlds(boolean sandboxWasDestroying) {
        UncertainStart start = this.uncertainStart;
        if (start == null) {
            return false;
        }
        boolean tickerDiffers = false;
        for (UncertainStart.OtherWorld world : start.others()) {
            MiningState mining = world.mining();
            if (mining.isDestroying != sandboxWasDestroying) {
                tickerDiffers = true;
            }
            if (mining.isDestroying) {
                mining.isDestroying = false;
                mining.destroyProgress = 0.0F;
            }
        }
        return tickerDiffers;
    }

    // - MultiPlayerGameMode.sameDestroyTarget, which does not ask whether the client is still breaking: after a -
    // - finished break the client goes on breaking a block that comes back at the same place without starting anew. -
    // - Any item may be the one the client started with while that is unknown -
    boolean sameDestroyTarget(SandboxPlayer player, BlockPos pos) {
        ItemStack selected = player.getMainHandItem();
        return pos.equals(this.mining.destroyBlockPos) && (!this.mining.destroyingItemKnown || ItemStack.isSameItemSameComponents(selected, this.mining.destroyingItem));
    }

    // - Why MultiPlayerGameMode.continueDestroyBlock could not have finished breaking the block at pos now, the only -
    // - place STOP_DESTROY_BLOCK comes from, or null when it could have. It counts down the delay after a finished or -
    // - creative break first and breaks with START_DESTROY_BLOCK in creative mode inside the world border; otherwise -
    // - it adds this tick's share of the breaking progress for the block it is breaking with the item it started with, -
    // - and finishes once the progress reaches the whole block. A block that turned into air ends the breaking -
    // - without a packet; the crosshair's check finds that one -
    @Nullable String whyNoFinish(SandboxLevel level, SandboxPlayer player, BlockPos pos) {
        if (this.mining.destroyDelay > 0) {
            return "within " + DESTROY_DELAY_TICKS + " ticks of its previous break, which a vanilla client waits out";
        }
        if (player.getAbilities().instabuild && level.getWorldBorder().isWithinBounds(pos)) {
            return "in creative mode, where a vanilla client breaks a block as it starts on it";
        }
        if (!pos.equals(this.mining.destroyBlockPos)) {
            return this.mining.isDestroying
                    ? "while it was breaking the block at " + this.mining.destroyBlockPos.toShortString()
                    : "which it had not started breaking";
        }
        if (this.mining.destroyingItemKnown && !ItemStack.isSameItemSameComponents(player.getMainHandItem(), this.mining.destroyingItem)) {
            return "with another item than the one it started breaking it with";
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        float progress = this.mining.destroyProgress + state.getDestroyProgress(player, player.level(), pos);
        if (progress >= 1.0F) {
            return null;
        }
        return String.format(Locale.ROOT, "at %.1f%% of its breaking progress", progress * PERCENT);
    }

    void useItemOn(SandboxLevel level, SandboxPlayer player, InteractionHand hand, BlockHitResult blockHit, int sequence, ClientTickPackets packets) {
        predict(level, sequence, packets, () -> this.performUseItemOn(level, player, hand, blockHit));
    }

    private InteractionResult performUseItemOn(SandboxLevel level, SandboxPlayer player, InteractionHand hand, BlockHitResult blockHit) {
        BlockPos pos = blockHit.getBlockPos();
        ItemStack itemStack = player.getItemInHand(hand);
        if (this.localPlayerMode == GameType.SPECTATOR) {
            return InteractionResult.CONSUME;
        }

        boolean haveSomethingInOurHands = !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty();
        boolean suppressUsingBlock = player.isSecondaryUseActive() && haveSomethingInOurHands;
        if (!suppressUsingBlock) {
            BlockState blockState = level.getBlockState(pos);
            if (!blockState.getBlock().requiredFeatures().isSubsetOf(level.enabledFeatures())) {
                return InteractionResult.FAIL;
            }

            InteractionResult itemUse = blockState.useItemOn(player.getItemInHand(hand), level, player, hand, blockHit);
            if (itemUse.consumesAction()) {
                return itemUse;
            }

            if (itemUse instanceof InteractionResult.TryEmptyHandInteraction && hand == InteractionHand.MAIN_HAND) {
                InteractionResult use = blockState.useWithoutItem(level, player, blockHit);
                if (use.consumesAction()) {
                    return use;
                }
            }
        }

        if (!itemStack.isEmpty() && !player.getCooldowns().isOnCooldown(itemStack)) {
            UseOnContext context = new UseOnContext(player, hand, blockHit);
            InteractionResult result;
            if (player.hasInfiniteMaterials()) {
                int count = itemStack.getCount();
                result = itemStack.useOn(context);
                itemStack.setCount(count);
            } else {
                result = itemStack.useOn(context);
                if (result instanceof InteractionResult.Success success) {
                    ItemStack resultItemStack = Objects.requireNonNullElseGet(success.heldItemTransformedTo(), () -> player.getItemInHand(hand));
                    if (resultItemStack != itemStack) {
                        player.setItemInHand(hand, resultItemStack);
                    }
                }
            }

            return result;
        }
        return InteractionResult.PASS;
    }

    // - The packet carries the rotation the client used, which the tick's movement packet reports as well -
    void useItem(SandboxLevel level, SandboxPlayer player, InteractionHand hand, int sequence, ClientTickPackets packets) {
        predict(level, sequence, packets, () -> {
            ItemStack itemStack = player.getItemInHand(hand);
            if (player.getCooldowns().isOnCooldown(itemStack)) {
                return;
            }

            InteractionResult resultHolder = itemStack.use(level, player, hand);
            ItemStack result;
            if (resultHolder instanceof InteractionResult.Success success) {
                result = Objects.requireNonNullElseGet(success.heldItemTransformedTo(), () -> player.getItemInHand(hand));
            } else {
                result = player.getItemInHand(hand);
            }

            if (result != itemStack) {
                player.setItemInHand(hand, result);
            }
        });
    }

    void attack(SandboxPlayer player, Entity entity) {
        player.attack(entity);
        this.finishAttack(player);
    }

    // - The rest of MultiPlayerGameMode.attack after Player.attack, which is all the sandbox can do for an entity it -
    // - does not know -
    void finishAttack(SandboxPlayer player) {
        player.resetAttackStrengthTicker();
        if (player.getAbilities().instabuild) {
            this.mining.destroyDelay = DESTROY_DELAY_TICKS;
        }
    }

    void interact(SandboxPlayer player, Entity entity, InteractionHand hand, Vec3 location) {
        if (this.localPlayerMode != GameType.SPECTATOR) {
            player.interactOn(entity, hand, location);
        }
    }

    // - MultiPlayerGameMode.piercingAttack; the swing and the sound only show something -
    void piercingAttack(SandboxPlayer player) {
        player.onAttack();
        player.postPiercingAttack();
    }

    void releaseUsingItem(SandboxPlayer player) {
        player.releaseUsingItem();
    }

    void dropItem(SandboxPlayer player, boolean all) {
        player.getInventory().removeFromSelected(all);
    }

    // - MultiPlayerGameMode.handleContainerInput: the client clicks in its menu and sends the slots that changed as -
    // - hashes. The sandbox clicks the same way and compares; a difference means the sandbox's items differ from the -
    // - client's. Returns the differences, empty when both agree -
    static List<String> handleContainerInput(ServerboundContainerClickPacket packet, SandboxPlayer player, HashedPatchMap.HashGenerator hashGenerator) {
        AbstractContainerMenu containerMenu = player.containerMenu;
        List<String> differences = new ArrayList<>();
        if (packet.containerId() != containerMenu.containerId) {
            differences.add("click in container " + packet.containerId() + " while the sandbox has " + containerMenu.containerId + " open");
            return differences;
        }
        // - The server ignores a click on a slot the menu does not have; the client's screens only click existing ones -
        if (!containerMenu.isValidSlotIndex(packet.slotNum())) {
            differences.add("click on slot " + packet.slotNum() + ", which the menu does not have");
            return differences;
        }
        if (packet.stateId() != containerMenu.getStateId()) {
            differences.add("menu state " + packet.stateId() + " instead of " + containerMenu.getStateId());
        }
        NonNullList<Slot> slots = containerMenu.slots;
        int slotCount = slots.size();
        List<ItemStack> itemsBeforeClick = new ArrayList<>(slotCount);

        for (Slot slot : slots) {
            itemsBeforeClick.add(slot.getItem().copy());
        }

        containerMenu.clicked(packet.slotNum(), packet.buttonNum(), packet.containerInput(), player);
        IntSet changedSlots = new IntOpenHashSet();

        for (int i = 0; i < slotCount; i++) {
            if (!ItemStack.matches(itemsBeforeClick.get(i), slots.get(i).getItem())) {
                changedSlots.add(i);
            }
        }

        Int2ObjectMap<HashedStack> reported = packet.changedSlots();
        IntSet allSlots = new IntOpenHashSet(changedSlots);
        allSlots.addAll(reported.keySet());
        allSlots.forEach(slotIndex -> {
            HashedStack reportedStack = reported.get(slotIndex);
            if (reportedStack == null) {
                differences.add("slot " + slotIndex + " changed only in the sandbox");
            } else if (slotIndex < 0 || slotIndex >= slotCount) {
                differences.add("slot " + slotIndex + " does not exist in the menu");
            } else if (!changedSlots.contains(slotIndex)) {
                differences.add("slot " + slotIndex + " changed only on the client");
            } else if (!reportedStack.matches(slots.get(slotIndex).getItem(), hashGenerator)) {
                differences.add("slot " + slotIndex + " holds different items");
            }
        });
        if (!packet.carriedItem().matches(containerMenu.getCarried(), hashGenerator)) {
            differences.add("carried item differs");
        }
        return differences;
    }

    // - The screens that change their menu before sending a button click (StonecutterScreen, EnchantmentScreen, -
    // - LoomScreen); the other screens only send it -
    static void handleContainerButtonClick(SandboxPlayer player, int containerId, int buttonId) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId == containerId && (menu instanceof StonecutterMenu || menu instanceof EnchantmentMenu || menu instanceof LoomMenu)) {
            menu.clickMenuButton(player, buttonId);
        }
    }

    // - MerchantScreen.postButtonClick selects the trade and moves the payment into the trade slots itself -
    static void handleSelectTrade(SandboxPlayer player, int tradeIndex) {
        if (player.containerMenu instanceof MerchantMenu menu) {
            menu.setSelectionHint(tradeIndex);
            menu.tryMoveItems(tradeIndex);
        }
    }

    // - AnvilScreen.onNameChanged renames the result itself before sending the name -
    static void handleRenameItem(SandboxPlayer player, String name) {
        if (player.containerMenu instanceof AnvilMenu menu) {
            menu.setItemName(name);
        }
    }

    // - CrafterScreen.updateSlotState toggles a crafting slot itself before sending it. Returns false for a slot -
    // - the screen could not have toggled -
    static boolean handleSlotStateChanged(SandboxPlayer player, int slotId, int containerId, boolean enabled) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != containerId || !(menu instanceof CrafterMenu crafterMenu)) {
            return true;
        }
        if (slotId < 0 || slotId >= crafterMenu.slots.size() || !(crafterMenu.getSlot(slotId) instanceof CrafterSlot)) {
            return false;
        }
        crafterMenu.setSlotState(slotId, enabled);
        return true;
    }

    // - BundleMouseActions.toggleSelectedBundleItem changes the bundle in the hovered slot and sends that slot's -
    // - index within its container. Returns false when that index does not identify one bundle in the open menu -
    static boolean handleSelectBundleItem(SandboxPlayer player, int containerSlot, int selectedItemIndex) {
        Slot found = null;
        for (Slot slot : player.containerMenu.slots) {
            if (slot.index == containerSlot && slot.getItem().has(DataComponents.BUNDLE_CONTENTS)) {
                if (found != null) {
                    return false;
                }
                found = slot;
            }
        }
        if (found == null) {
            return false;
        }
        BundleItem.toggleSelectedItem(found.getItem(), selectedItemIndex);
        return true;
    }
}
