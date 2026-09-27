package io.github.hellotta.clauac.api;

import io.github.hellotta.clauac.simulation.api.Check;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

// - Called when a client tick of a player failed one of ClauAC's checks, once for every check the tick failed, before -
// - ClauAC responds to it. It is asynchronous: ClauAC calls it from a simulation thread right when the tick's result -
// - is known, so listeners have to be quick and must not touch the world or the player's state, only read what the -
// - event holds and the player's name and id. Cancelling it keeps ClauAC from responding to this flag: no alert, and -
// - neither a setback nor the tick's attacks and interactions kept from the server unless another flag of the same -
// - tick that is not cancelled asks for it -
public final class ClauACFlagEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Check check;
    private final String detail;
    private final long clientTick;
    private boolean cancelled;

    public ClauACFlagEvent(Player player, Check check, String detail, long clientTick) {
        super(true);
        this.player = player;
        this.check = check;
        this.detail = detail;
        this.clientTick = clientTick;
    }

    public Player getPlayer() {
        return this.player;
    }

    // - The check the tick failed; its display name is what alerts and the configuration use -
    public Check getCheck() {
        return this.check;
    }

    // - What exactly failed, in words -
    public String getDetail() {
        return this.detail;
    }

    // - The client tick that failed, counted from the connection's first tick as the report files count it -
    public long getClientTick() {
        return this.clientTick;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
