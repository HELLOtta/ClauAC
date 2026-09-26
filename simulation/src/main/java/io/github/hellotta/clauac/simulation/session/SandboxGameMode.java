package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import io.github.hellotta.clauac.simulation.world.SandboxBlockPredictions;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
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

// - Port of the client-only MultiPlayerGameMode: the local game mode and everything the client does on its own -
// - when the player breaks, uses, attacks, interacts, clicks in a menu or drops an item. The client predicts the -
// - results locally before the server answers, and those predictions change the player's world, inventory and -
// - movement, so the sandbox performs them too. The sandbox learns from the client's packets what the player did; -
// - what the client decided from its keys alone (how long mining took, whether a swing hit anything) is followed -
// - from the packets that decision produced -
final class SandboxGameMode {

    // - MultiPlayerGameMode sets destroyDelay to 5 after a finished or creative break -
    private static final int DESTROY_DELAY_TICKS = 5;

    private GameType localPlayerMode = GameType.DEFAULT_MODE;
    // - MultiPlayerGameMode's survival mining state -
    private boolean isDestroying;
    private BlockPos destroyBlockPos = new BlockPos(-1, -1, -1);
    private ItemStack destroyingItem = ItemStack.EMPTY;
    private float destroyProgress;
    private int destroyDelay;

    GameType localPlayerMode() {
        return this.localPlayerMode;
    }

    boolean isSpectator() {
        return this.localPlayerMode == GameType.SPECTATOR;
    }

    // - handleLogin creates a new MultiPlayerGameMode -
    void reset() {
        this.localPlayerMode = GameType.DEFAULT_MODE;
        this.isDestroying = false;
        this.destroyBlockPos = new BlockPos(-1, -1, -1);
        this.destroyingItem = ItemStack.EMPTY;
        this.destroyProgress = 0.0F;
        this.destroyDelay = 0;
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
        if (player.blockActionRestricted(level, pos, this.localPlayerMode)) {
            return false;
        }

        BlockState oldState = level.getBlockState(pos);
        if (!player.getMainHandItem().canDestroyBlock(oldState, level, pos, player)) {
            return false;
        }

        Block oldBlock = oldState.getBlock();
        if (oldBlock instanceof GameMasterBlock && !player.canUseGameMasterBlocks()) {
            return false;
        }

        if (oldState.isAir()) {
            return false;
        }

        oldBlock.playerWillDestroy(level, pos, oldState, player);
        FluidState fluidState = level.getFluidState(pos);
        boolean changed = level.setBlock(pos, fluidState.createLegacyBlock(), 11);
        if (changed) {
            oldBlock.destroy(level, pos, oldState);
        }

        return changed;
    }

    // - START_DESTROY_BLOCK comes from MultiPlayerGameMode.startDestroyBlock, or from continueDestroyBlock in creative -
    // - mode, which predicts the same break. When the crosshair moved on while mining, the progress on the old block -
    // - is still set here and keeps the new block from being attacked, as on the client -
    void startDestroyBlock(SandboxLevel level, SandboxPlayer player, BlockPos pos, int sequence, ClientTickPackets packets) {
        if (player.getAbilities().instabuild) {
            predict(level, sequence, packets, () -> this.destroyBlock(level, player, pos));
            this.destroyDelay = DESTROY_DELAY_TICKS;
            return;
        }
        BlockState state = level.getBlockState(pos);
        predict(level, sequence, packets, () -> {
            boolean notAir = !state.isAir();
            if (notAir && this.destroyProgress == 0.0F) {
                state.attack(level, pos, player);
            }

            if (notAir && state.getDestroyProgress(player, player.level(), pos) >= 1.0F) {
                this.destroyBlock(level, player, pos);
            } else {
                this.isDestroying = true;
                this.destroyBlockPos = pos;
                this.destroyingItem = player.getMainHandItem();
                this.destroyProgress = 0.0F;
            }
        });
    }

    // - STOP_DESTROY_BLOCK: continueDestroyBlock found the block broken -
    void finishDestroyBlock(SandboxLevel level, SandboxPlayer player, BlockPos pos, int sequence, ClientTickPackets packets) {
        if (!this.isDestroying || !pos.equals(this.destroyBlockPos)) {
            packets.notes.add("the client finished breaking " + pos.toShortString() + " without having started it");
        }
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

    // - A swing (ServerboundPunchPacket) that no attack or block packet accompanied: Minecraft.startAttack missing, or -
    // - Minecraft.continueAttack mining on. Which one it was is what the client's crosshair pointed at, which the -
    // - sandbox computes the way Minecraft.pick does -
    void swingAlone(SandboxLevel level, SandboxPlayer player, HitResult hitResult) {
        if (hitResult instanceof BlockHitResult blockHit && hitResult.getType() == HitResult.Type.BLOCK && !level.getBlockState(blockHit.getBlockPos()).isAir()) {
            // - continueDestroyBlock -
            if (this.destroyDelay > 0) {
                this.destroyDelay--;
            } else if (this.sameDestroyTarget(player, blockHit.getBlockPos())) {
                BlockState state = level.getBlockState(blockHit.getBlockPos());
                this.destroyProgress = this.destroyProgress + state.getDestroyProgress(player, player.level(), blockHit.getBlockPos());
            }
        } else if (hitResult.getType() != HitResult.Type.ENTITY) {
            // - startAttack on nothing; an entity out of a weapon's attack range is not attacked and not reset -
            player.resetAttackStrengthTicker();
        }
    }

    private boolean sameDestroyTarget(SandboxPlayer player, BlockPos pos) {
        ItemStack selected = player.getMainHandItem();
        return this.isDestroying && pos.equals(this.destroyBlockPos) && ItemStack.isSameItemSameComponents(selected, this.destroyingItem);
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
