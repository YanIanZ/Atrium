package dev.yanianz.atrium.api.itemtools;

import java.util.Objects;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired on the item frame's region thread before {@code /itemframe} changes it. Cancelling the event
 * prevents the change, so protection plugins can deny edits inside claims.
 */
public final class ItemFrameEditEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    /** The property the player asked to change. */
    public enum Action {
        INVISIBLE,
        VISIBLE,
        FIXED,
        UNFIXED
    }

    private final ItemFrame frame;
    private final Action action;
    private boolean cancelled;

    public ItemFrameEditEvent(Player player, ItemFrame frame, Action action) {
        super(player);
        this.frame = Objects.requireNonNull(frame, "frame");
        this.action = Objects.requireNonNull(action, "action");
    }

    public ItemFrame frame() {
        return frame;
    }

    public Action action() {
        return action;
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
