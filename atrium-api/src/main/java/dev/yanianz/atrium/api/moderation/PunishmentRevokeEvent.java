package dev.yanianz.atrium.api.moderation;

import java.util.List;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Fired before active punishments are lifted early (unban, unbanip, unmute, unjail, unfreeze, delwarn,
 * clearwarns). Cancelling it leaves every listed punishment in effect.
 *
 * <p>May be fired asynchronously ({@link #isAsynchronous()}); do not touch the world from a listener.
 */
public final class PunishmentRevokeEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final List<Punishment> punishments;
    private final @Nullable UUID actor;
    private final String actorName;
    private boolean cancelled;

    public PunishmentRevokeEvent(boolean async, List<Punishment> punishments, @Nullable UUID actor, String actorName) {
        super(async);
        this.punishments = List.copyOf(punishments);
        this.actor = actor;
        this.actorName = actorName;
    }

    /** The records that are about to be revoked (still active). */
    public List<Punishment> punishments() {
        return punishments;
    }

    /** {@code null} for the console or a plugin. */
    public @Nullable UUID actor() {
        return actor;
    }

    public String actorName() {
        return actorName;
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
