package dev.yanianz.atrium.api.warps;

import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Called before a warp is deleted. Cancelling keeps the warp. */
public class WarpDeleteEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender actor;
    private final String name;
    private boolean cancelled;

    public WarpDeleteEvent(CommandSender actor, String name) {
        this.actor = actor;
        this.name = name;
    }

    public CommandSender getActor() {
        return actor;
    }

    public String getName() {
        return name;
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
