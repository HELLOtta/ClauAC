package io.github.hellotta.clauac.simulation.session;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.world.entity.player.Input;
import org.jspecify.annotations.Nullable;

// - What the sandbox knows about the client's screens, and what an open screen leaves the client's player. A screen -
// - that opens lets go of every key and of the mouse (Gui.setScreen calls KeyMapping.releaseAll and -
// - MouseHandler.releaseMouse; ToggleKeyMapping.release lets go of a toggled key as well), and while it is open no -
// - key or mouse button reaches a key mapping (KeyboardHandler.keyPress hands keys to the screen, MouseHandler.onButton -
// - sets key mappings only without a screen) and the mouse turns nobody (MouseHandler.handleAccumulatedMovement turns -
// - the player only while it grabs the mouse). KeyboardInput.tick therefore reads no key, and LocalPlayer.tick -
// - reports none, but for the jump an auto jump adds in the tick after it triggered (LocalPlayer.aiStep): an auto jump -
// - triggers only while a movement key is held (LocalPlayer.canAutoJump), so that jump falls into the first tick of a -
// - screen at most. -
// -
// - A container screen is open from the server's packet that opens it (MenuScreens.create, -
// - ClientPacketListener.handleMountScreenOpen) until the client closes it, which every container screen does through -
// - LocalPlayer.closeContainer and its packet (AbstractContainerScreen.onClose and tick, the anvil, beacon and -
// - lectern screens, LocalPlayer.handlePortalTransitionEffect), or the server closes it -
// - (LocalPlayer.clientSideCloseContainer) or opens another one. The player's own inventory screen opens without a -
// - packet, in the key handling of a tick (Minecraft.handleKeybinds, which runs only without a screen); only what the -
// - client does in it shows that it is open: from then on until the client closes it, and in the tick before, whose -
// - key handling opened it at the latest. Of the other screens the server opens, a book, a sign's text, a dialog, a -
// - resource pack prompt and the death, credits and demo screens may replace the open screen and bring it back without -
// - a packet (ClientCommonPacketListenerImpl.clearDialog, the resource pack prompt's parent screen); after one of them -
// - the sandbox does not know until a screen opens or closes again. Screens the client opens on its own (the chat, -
// - the pause menu) are never known; they leave nothing to check. Used by the connection's tasks only, which run one -
// - at a time -
final class ScreenTracker {

    private enum State {
        // - No screen the sandbox can know of is open: the client or the server closed the last one, or the player -
        // - joined its level -
        CLOSED,
        // - A container screen or the player's inventory screen is open -
        OPEN,
        // - A screen the server opened may have replaced the open one and brought it back since -
        UNKNOWN
    }

    // - The keys of a tick whose only key is the jump an auto jump of the tick before added (ClientInput.makeJump) -
    private static final Input AUTO_JUMP_KEYS = new Input(false, false, false, false, true, false, false);

    private State state = State.CLOSED;
    // - OPEN: the first client tick that ran with the screen open, from which on every tick did. CLOSED: the first -
    // - client tick whose key handling could have opened the player's inventory screen again -
    private long stateTick = 1L;

    // - The player joined a level, or the client or the server closed the screen: LocalPlayer.closeContainer and -
    // - LocalPlayer.clientSideCloseContainer close whichever screen is open. nextTick is the first client tick to run -
    // - after it -
    void closed(long nextTick) {
        this.state = State.CLOSED;
        this.stateTick = nextTick;
    }

    // - The server opened a container screen. It replaces the open screen, and keys and mouse stay released when a -
    // - screen was open already -
    void containerScreenOpened(long nextTick) {
        if (this.state != State.OPEN) {
            this.open(nextTick);
        }
    }

    // - The server opened a screen that may replace the open one and bring it back without a packet -
    void screenOutOfSight() {
        this.state = State.UNKNOWN;
    }

    // - The client did something in its open menu that only an open container screen does -
    // - (AbstractContainerScreen.slotClicked, RecipeBookComponent, BundleMouseActions), so a screen is open now: the -
    // - player's inventory screen when the sandbox knew of none. action says what it did there, lastEndedTick is the -
    // - client tick that ended last and lastTickKeys the keys that tick reported when it ran the player, null -
    // - otherwise. Returns why no vanilla client does that, when it does not -
    Optional<String> screenShown(String action, long lastEndedTick, @Nullable Input lastTickKeys) {
        switch (this.state) {
            case OPEN -> {
                return Optional.empty();
            }
            case UNKNOWN -> {
                this.open(lastEndedTick + 1L);
                return Optional.empty();
            }
            case CLOSED -> {
                if (lastEndedTick < this.stateTick) {
                    this.open(lastEndedTick + 1L);
                    return Optional.of("the client " + action + " with no tick since its screen closed, where that screen only opens in the key"
                            + " handling of a tick (Minecraft.handleKeybinds)");
                }
                if (lastTickKeys == null) {
                    this.open(lastEndedTick + 1L);
                    return Optional.empty();
                }
                this.open(lastEndedTick);
                if (pressesKeys(lastTickKeys, true)) {
                    return Optional.of(String.format(Locale.ROOT,
                            "the client %s right after client tick %d, in which it pressed %s, where that screen only opens in the key handling of a"
                                    + " tick (Minecraft.handleKeybinds) and lets go of every key before the player moves (Gui.setScreen)",
                            action, lastEndedTick, describe(lastTickKeys)));
                }
                return Optional.empty();
            }
            default -> throw new IllegalStateException("Unknown screen state " + this.state);
        }
    }

    // - Why the keys the client reported for this tick are none it has with a screen open, when they are not -
    Optional<String> checkKeys(long tick, Input keys) {
        if (this.state != State.OPEN || tick < this.stateTick || !pressesKeys(keys, tick == this.stateTick)) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT,
                "the client pressed %s while a screen was open since client tick %d, where a vanilla client lets go of every key as a screen"
                        + " opens and takes no key press until it closes (Gui.setScreen, KeyboardHandler.keyPress)",
                describe(keys), this.stateTick));
    }

    // - Why a rotation the client had in this tick, with the screen in the state it is now, is none its mouse can have -
    // - turned it to since the tick before, when it is not: the screen was open from that tick's key handling on, and -
    // - from is the rotation the player had then. what says where the client reported the rotation -
    Optional<String> checkTurn(long tick, float fromYRot, float fromXRot, float toYRot, float toXRot, String what) {
        if (this.state != State.OPEN || this.stateTick > tick - 1L || toYRot == fromYRot && toXRot == fromXRot) {
            return Optional.empty();
        }
        return Optional.of(String.format(Locale.ROOT,
                "the client turned from %.3f %.3f to %.3f %.3f in %s while a screen was open since client tick %d, where a vanilla client's mouse"
                        + " turns nobody while a screen is open (MouseHandler.handleAccumulatedMovement)",
                fromYRot, fromXRot, toYRot, toXRot, what, this.stateTick));
    }

    private void open(long firstTick) {
        this.state = State.OPEN;
        this.stateTick = firstTick;
    }

    // - Whether a vanilla client cannot have these keys with a screen open; autoJumpAllowed when the tick can be the -
    // - first one of the screen -
    private static boolean pressesKeys(Input keys, boolean autoJumpAllowed) {
        return !keys.equals(Input.EMPTY) && !(autoJumpAllowed && keys.equals(AUTO_JUMP_KEYS));
    }

    private static String describe(Input keys) {
        List<String> pressed = new ArrayList<>();
        if (keys.forward()) {
            pressed.add("forward");
        }
        if (keys.backward()) {
            pressed.add("backward");
        }
        if (keys.left()) {
            pressed.add("left");
        }
        if (keys.right()) {
            pressed.add("right");
        }
        if (keys.jump()) {
            pressed.add("jump");
        }
        if (keys.shift()) {
            pressed.add("sneak");
        }
        if (keys.sprint()) {
            pressed.add("sprint");
        }
        if (pressed.size() == 1) {
            return "the key " + pressed.getFirst();
        }
        return "the keys " + String.join(", ", pressed.subList(0, pressed.size() - 1)) + " and " + pressed.getLast();
    }
}
