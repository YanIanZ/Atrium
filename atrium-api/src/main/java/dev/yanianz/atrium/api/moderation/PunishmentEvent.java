package dev.yanianz.atrium.api.moderation;

import java.util.Objects;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a punishment is stored or enforced. Cancelling it prevents the punishment entirely.
 * Listeners may replace the request (for example to change the reason or the duration), but not its kind
 * or target.
 *
 * <p>May be fired asynchronously ({@link #isAsynchronous()}); do not touch the world from a listener.
 */
public final class PunishmentEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private PunishmentRequest request;
    private boolean cancelled;

    public PunishmentEvent(boolean async, PunishmentRequest request) {
        super(async);
        this.request = Objects.requireNonNull(request, "request");
    }

    public PunishmentRequest request() {
        return request;
    }

    /** Replaces the request. Type, target and IP must stay the same. */
    public void request(PunishmentRequest replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (replacement.type() != request.type() || !Objects.equals(replacement.target(), request.target())
                || !Objects.equals(replacement.ip(), request.ip())) {
            throw new IllegalArgumentException("Type, target and IP of a punishment cannot be changed");
        }
        this.request = replacement;
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
