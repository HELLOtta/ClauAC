package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import io.github.hellotta.clauac.simulation.world.SandboxBlockPredictions;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    // - MultiPlayerGameMode's survival mining state -
    private boolean isDestroying;
    private BlockPos destroyBlockPos = new BlockPos(-1, -1, -1);
    private ItemStack destroyingItem = ItemStack.EMPTY;
    private float destroyProgress;
    private int destroyDelay;
    // - Whether destroyingItem is the item the client started breaking with, which a start does not always tell -
    // - (see startDestroyBlock) until the client goes on breaking with the item it holds -
    private boolean destroyingItemKnown = true;
    // - Whether the mining state above is the client's. A start that another hotbar item would have ended at once -
    // - where the sandbox's did not, or the other way round (see startDestroyBlock), leaves the client breaking -
    // - another block than the sandbox, and so does a swing whose block the sandbox's crosshair may have missed (see -
    // - swingAlone). The next start whose outcome is known, or the next finished break, tells the state again -
    private boolean miningStateKnown = true;
    // - Blocks the client may have broken otherwise than the sandbox (see startDestroyBlock) until the server -
    // - acknowledged the start's prediction, and those it acknowledged since the client's last tick: ending a -
    // - prediction may put the client's player back (SandboxLevel.syncBlockState), which shows in the next tick -
    private final List<UncertainBreak> uncertainBreaks = new ArrayList<>();
    private final List<UncertainBreak> acknowledgedUncertainBreaks = new ArrayList<>();

    // - A block the client may have broken at once where the sandbox did not, or the other way round, with the -
    // - prediction sequence of the start that did or did not break it -
    record UncertainBreak(BlockPos pos, int sequence) {
    }

    // - What a start of breaking does with the item held: whether it breaks the block at once rather than start -
    // - breaking it (in creative mode it always does), and whether that break changes the block (destroyBlock) -
    private record StartOutcome(boolean breaksAtOnce, boolean changesBlock) {
    }

    GameType localPlayerMode() {
        return this.localPlayerMode;
    }

    // - MultiPlayerGameMode.isDestroying -
    boolean isDestroying() {
        return this.isDestroying;
    }

    boolean isSpectator() {
        return this.localPlayerMode == GameType.SPECTATOR;
    }

    boolean miningStateKnown() {
        return this.miningStateKnown;
    }

    // - The blocks the client may have broken otherwise than the sandbox whose start the server has not -
    // - acknowledged yet -
    List<UncertainBreak> uncertainBreaks() {
        return this.uncertainBreaks;
    }

    // - Those and the ones the server acknowledged since the client's last tick -
    List<UncertainBreak> recentUncertainBreaks() {
        List<UncertainBreak> recent = new ArrayList<>(this.uncertainBreaks);
        recent.addAll(this.acknowledgedUncertainBreaks);
        return recent;
    }

    // - ClientboundBlockChangedAckPacket: the server acknowledged the predictions through this sequence, after which -
    // - the client's blocks are the server's -
    void onBlockChangedAck(int sequence) {
        this.uncertainBreaks.removeIf(uncertain -> {
            if (uncertain.sequence() > sequence) {
                return false;
            }
            this.acknowledgedUncertainBreaks.add(uncertain);
            return true;
        });
    }

    // - A client tick was compared, with the acknowledged uncertain blocks in view -
    void forgetAcknowledgedUncertainBreaks() {
        this.acknowledgedUncertainBreaks.clear();
    }

    // - handleLogin creates a new MultiPlayerGameMode, and the new level's predictions start over -
    void reset() {
        this.localPlayerMode = GameType.DEFAULT_MODE;
        this.isDestroying = false;
        this.destroyBlockPos = new BlockPos(-1, -1, -1);
        this.destroyingItem = ItemStack.EMPTY;
        this.destroyProgress = 0.0F;
        this.destroyDelay = 0;
        this.destroyingItemKnown = true;
        this.miningStateKnown = true;
        this.uncertainBreaks.clear();
        this.acknowledgedUncertainBreaks.clear();
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
    // - tick reports, or no tick when the slot changed back before. Where another hotbar item would have broken the -
    // - block at once and the sandbox's does not, or the other way round, the client's block is unknown until the -
    // - server acknowledges the start, and where that decides whether the client starts breaking, so is its mining -
    // - state -
    void startDestroyBlock(SandboxLevel level, SandboxPlayer player, BlockPos pos, int sequence, boolean itemKnown, ClientTickPackets packets) {
        if (player.getAbilities().instabuild) {
            predict(level, sequence, packets, () -> {
                if (!itemKnown) {
                    this.compareOtherHotbarItems(level, player, pos, level.getBlockState(pos), sequence);
                }
                this.destroyBlock(level, player, pos);
            });
            this.destroyDelay = DESTROY_DELAY_TICKS;
            return;
        }
        BlockState state = level.getBlockState(pos);
        predict(level, sequence, packets, () -> {
            boolean notAir = !state.isAir();
            if (notAir && this.destroyProgress == 0.0F) {
                state.attack(level, pos, player);
            }

            boolean miningOutcomeKnown = itemKnown || this.compareOtherHotbarItems(level, player, pos, state, sequence);
            if (notAir && state.getDestroyProgress(player, player.level(), pos) >= 1.0F) {
                this.destroyBlock(level, player, pos);
            } else {
                this.isDestroying = true;
                this.destroyBlockPos = pos;
                this.destroyingItem = player.getMainHandItem();
                this.destroyProgress = 0.0F;
                this.destroyingItemKnown = itemKnown || !anyOtherHotbarItem(player, () -> true);
                if (miningOutcomeKnown) {
                    this.miningStateKnown = true;
                }
            }
            if (!miningOutcomeKnown) {
                this.miningStateKnown = false;
            }
        });
    }

    // - What the start does with the item the sandbox holds against every other hotbar item the client may have -
    // - started with (see startDestroyBlock), which are the ones startDestroyBlock sends a start with: the game mode -
    // - lets the player break the block with them (Player.blockActionRestricted). A difference in the block keeps the -
    // - block unknown until the server acknowledges the start. Returns whether all agree on breaking it at once -
    private boolean compareOtherHotbarItems(SandboxLevel level, SandboxPlayer player, BlockPos pos, BlockState state, int sequence) {
        StartOutcome held = this.startOutcome(level, player, pos, state);
        if (anyOtherHotbarItem(player, () -> !player.blockActionRestricted(level, pos, this.localPlayerMode)
                && this.startOutcome(level, player, pos, state).changesBlock() != held.changesBlock())) {
            this.uncertainBreaks.add(new UncertainBreak(pos.immutable(), sequence));
        }
        return !anyOtherHotbarItem(player, () -> !player.blockActionRestricted(level, pos, this.localPlayerMode)
                && this.startOutcome(level, player, pos, state).breaksAtOnce() != held.breaksAtOnce());
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
    // - block with the item it holds, whatever the sandbox thought, and its mining state is known again -
    void finishDestroyBlock(SandboxLevel level, SandboxPlayer player, BlockPos pos, int sequence, ClientTickPackets packets) {
        this.destroyBlockPos = pos;
        this.destroyingItem = player.getMainHandItem();
        this.destroyingItemKnown = true;
        this.miningStateKnown = true;
        this.isDestroying = false;
        predict(level, sequence, packets, () -> this.destroyBlock(level, player, pos));
        this.destroyProgress = 0.0F;
        this.destroyDelay = DESTROY_DELAY_TICKS;
    }

    // - ABORT_DESTROY_BLOCK: either stopDestroyBlock (key released or crosshair on nothing) or the start of mining -
    // - another block, which the following START_DESTROY_BLOCK tells -
    void abortDestroyBlock(SandboxPlayer player, boolean switchingTarget) {
        if (switchingTarget) {
            return;
        }
        if (this.isDestroying) {
            this.isDestroying = false;
            this.destroyProgress = 0.0F;
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
    // - with the item it holds. What the client's crosshair pointed at is computed the way Minecraft.pick does; where -
    // - the client may have seen another block (crosshairKnown), the mining state is no longer known -
    void swingAlone(SandboxLevel level, SandboxPlayer player, HitResult hitResult, boolean continuesBreaking, boolean crosshairKnown) {
        if (hitResult instanceof BlockHitResult blockHit && hitResult.getType() == HitResult.Type.BLOCK && !level.getBlockState(blockHit.getBlockPos()).isAir()) {
            if (!continuesBreaking) {
                return;
            }
            if (!crosshairKnown) {
                this.miningStateKnown = false;
            }
            // - continueDestroyBlock -
            if (this.destroyDelay > 0) {
                this.destroyDelay--;
            } else if (this.sameDestroyTarget(player, blockHit.getBlockPos())) {
                BlockState state = level.getBlockState(blockHit.getBlockPos());
                this.destroyProgress = this.destroyProgress + state.getDestroyProgress(player, player.level(), blockHit.getBlockPos());
                this.destroyingItem = player.getMainHandItem();
                this.destroyingItemKnown = true;
            }
        } else if (hitResult.getType() != HitResult.Type.ENTITY) {
            // - startAttack on nothing; an entity out of a weapon's attack range is not attacked and not reset -
            player.resetAttackStrengthTicker();
        }
    }

    // - MultiPlayerGameMode.sameDestroyTarget, which does not ask whether the client is still breaking: after a -
    // - finished break the client goes on breaking a block that comes back at the same place without starting anew. -
    // - Any item may be the one the client started with while that is unknown -
    boolean sameDestroyTarget(SandboxPlayer player, BlockPos pos) {
        ItemStack selected = player.getMainHandItem();
        return pos.equals(this.destroyBlockPos) && (!this.destroyingItemKnown || ItemStack.isSameItemSameComponents(selected, this.destroyingItem));
    }

    // - Why MultiPlayerGameMode.continueDestroyBlock could not have finished breaking the block at pos now, the only -
    // - place STOP_DESTROY_BLOCK comes from, or null when it could have. It counts down the delay after a finished or -
    // - creative break first and breaks with START_DESTROY_BLOCK in creative mode inside the world border; otherwise -
    // - it adds this tick's share of the breaking progress for the block it is breaking with the item it started with, -
    // - and finishes once the progress reaches the whole block. A block that turned into air ends the breaking -
    // - without a packet; the crosshair's check finds that one. Only meaningful while the mining state is known -
    // - (miningStateKnown) -
    @Nullable String whyNoFinish(SandboxLevel level, SandboxPlayer player, BlockPos pos) {
        if (this.destroyDelay > 0) {
            return "within " + DESTROY_DELAY_TICKS + " ticks of its previous break, which a vanilla client waits out";
        }
        if (player.getAbilities().instabuild && level.getWorldBorder().isWithinBounds(pos)) {
            return "in creative mode, where a vanilla client breaks a block as it starts on it";
        }
        if (!pos.equals(this.destroyBlockPos)) {
            return this.isDestroying ? "while it was breaking the block at " + this.destroyBlockPos.toShortString() : "which it had not started breaking";
        }
        if (this.destroyingItemKnown && !ItemStack.isSameItemSameComponents(player.getMainHandItem(), this.destroyingItem)) {
            return "with another item than the one it started breaking it with";
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return null;
        }
        float progress = this.destroyProgress + state.getDestroyProgress(player, player.level(), pos);
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
            this.destroyDelay = DESTROY_DELAY_TICKS;
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
