package io.github.hellotta.clauac.simulation.session;

import io.github.hellotta.clauac.simulation.player.SandboxPlayer;
import io.github.hellotta.clauac.simulation.world.SandboxLevel;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetDataPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundCooldownPacket;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.network.protocol.game.ClientboundMountScreenOpenPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import net.minecraft.world.inventory.HorseInventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.NautilusInventoryMenu;

// - Port of the inventory and menu handlers of the client-only ClientPacketListener, and of the screen changes that -
// - swap the player's open menu (MenuScreens.create, Minecraft.setScreen, AbstractContainerScreen.removed). The -
// - player's items decide what it holds, wears and uses, and with that how it moves. The creative inventory screen -
// - makes the client ignore cursor updates and track its slots differently; the client opens that screen without -
// - telling the server, so those differences only show up in the checked clicks (see SandboxGameMode) -
final class ContainerHandlers {

    private ContainerHandlers() {
    }

    static void handleContainerSetSlot(ClientboundContainerSetSlotPacket packet, SandboxPlayer player) {
        int slot = packet.getSlot();
        if (packet.getContainerId() == 0) {
            player.inventoryMenu.setItem(slot, packet.getStateId(), packet.getItem());
        } else if (packet.getContainerId() == player.containerMenu.containerId) {
            player.containerMenu.setItem(slot, packet.getStateId(), packet.getItem());
        }
    }

    static void handleSetCursorItem(ClientboundSetCursorItemPacket packet, SandboxPlayer player) {
        player.containerMenu.setCarried(packet.contents());
    }

    static void handleSetPlayerInventory(ClientboundSetPlayerInventoryPacket packet, SandboxPlayer player) {
        player.getInventory().setItem(packet.slot(), packet.contents());
    }

    // - Returns whether the packet replaced the whole inventory menu -
    static boolean handleContainerContent(ClientboundContainerSetContentPacket packet, SandboxPlayer player) {
        if (packet.containerId() == 0) {
            player.inventoryMenu.initializeContents(packet.stateId(), packet.items(), packet.carriedItem());
            return true;
        }
        if (packet.containerId() == player.containerMenu.containerId) {
            player.containerMenu.initializeContents(packet.stateId(), packet.items(), packet.carriedItem());
        }
        return false;
    }

    static void handleSetHeldSlot(ClientboundSetHeldSlotPacket packet, SandboxPlayer player) {
        if (Inventory.isHotbarSlot(packet.slot())) {
            player.getInventory().setSelectedSlot(packet.slot());
        }
    }

    static void handleContainerSetData(ClientboundContainerSetDataPacket packet, SandboxPlayer player) {
        if (player.containerMenu.containerId == packet.getContainerId()) {
            player.containerMenu.setData(packet.getId(), packet.getValue());
        }
    }

    static void handleItemCooldown(ClientboundCooldownPacket packet, SandboxPlayer player) {
        if (packet.duration() == 0) {
            player.getCooldowns().removeCooldown(packet.cooldownGroup());
        } else {
            player.getCooldowns().addCooldown(packet.cooldownGroup(), packet.duration());
        }
    }

    // - The trades decide which payment MerchantScreen moves into the trade slots when the player picks one -
    static void handleMerchantOffers(ClientboundMerchantOffersPacket packet, SandboxPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (packet.getContainerId() == menu.containerId && menu instanceof MerchantMenu merchantMenu) {
            merchantMenu.setOffers(packet.getOffers());
            merchantMenu.setXp(packet.getVillagerXp());
            merchantMenu.setMerchantLevel(packet.getVillagerLevel());
            merchantMenu.setShowProgressBar(packet.showProgress());
            merchantMenu.setCanRestock(packet.canRestock());
        }
    }

    // - MenuScreens.create: every menu type has a screen on the client, which creates the menu and makes it the -
    // - player's open menu -
    static void handleOpenScreen(ClientboundOpenScreenPacket packet, SandboxPlayer player) {
        AbstractContainerMenu menu = packet.getType().create(packet.getContainerId(), player.getInventory());
        replaceMenu(player, menu);
    }

    static void handleMountScreenOpen(ClientboundMountScreenOpenPacket packet, SandboxLevel level, SandboxPlayer player) {
        Entity entity = level.getEntity(packet.getEntityId());
        int inventoryColumns = packet.getInventoryColumns();
        SimpleContainer container = new SimpleContainer(AbstractMountInventoryMenu.getInventorySize(inventoryColumns));
        if (entity instanceof AbstractHorse horse) {
            replaceMenu(player, new HorseInventoryMenu(packet.getContainerId(), player.getInventory(), container, horse, inventoryColumns));
        } else if (entity instanceof AbstractNautilus nautilus) {
            replaceMenu(player, new NautilusInventoryMenu(packet.getContainerId(), player.getInventory(), container, nautilus, inventoryColumns));
        }
    }

    // - The new screen replaces the open one, whose menu is then removed. A container screen was open whenever the -
    // - open menu is not the player's own; the player's own inventory screen opens without a packet, so its removal -
    // - (which only clears the crafting result slot) cannot be followed -
    private static void replaceMenu(SandboxPlayer player, AbstractContainerMenu menu) {
        AbstractContainerMenu previous = player.containerMenu;
        player.containerMenu = menu;
        if (previous != player.inventoryMenu) {
            previous.removed(player);
        }
    }

    // - LocalPlayer.clientSideCloseContainer, which also closes the screen and so removes its menu -
    static void handleContainerClose(ClientboundContainerClosePacket packet, SandboxPlayer player) {
        closeScreen(player, false);
    }

    // - The client closed its screen and told the server (LocalPlayer.closeContainer); a close of menu 0 means the -
    // - player's own inventory screen was open -
    static void closeScreen(SandboxPlayer player, boolean inventoryScreenWasOpen) {
        AbstractContainerMenu previous = player.containerMenu;
        player.clientSideCloseContainer();
        if (previous != player.inventoryMenu || inventoryScreenWasOpen) {
            previous.removed(player);
        }
    }
}
