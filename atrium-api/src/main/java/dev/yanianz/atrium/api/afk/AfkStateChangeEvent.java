package dev.yanianz.atrium.api.afk;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired on the player's own thread <b>before</b> an AFK state change is applied. Cancelling it leaves the
 * player in the current state. The module's own {@code /afk} command, the idle timer and
 * {@link AfkService#setAfk} all go through this event.
 */
public class AfkStateChangeEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean entering;
    private final AfkReason reason;
    private boolean cancelled;

    public AfkStateChangeEvent(Player player, boolean entering, AfkReason reason) {
        super(player);
        this.entering = entering;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /** True when the player is about to become AFK, false when the AFK state is about to end. */
    public boolean isEntering() {
        return entering;
    }

    public AfkReason getReason() {
        return reason;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
