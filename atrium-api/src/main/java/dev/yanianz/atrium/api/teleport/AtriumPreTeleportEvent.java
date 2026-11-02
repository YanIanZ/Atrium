package dev.yanianz.atrium.api.teleport;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Called on the player's thread when an Atrium teleport is requested, <b>before</b> anything happens:
 * before the warmup starts, before the cooldown is set and before the /back location is recorded.
 * Cancelling it stops the teleport with {@link TeleportService.TeleportResult#CANCELLED_EVENT}; the
 * canceller is responsible for telling the player why. The destination may be changed.
 */
public class AtriumPreTeleportEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Location from;
    private final TeleportService.TeleportCause cause;
    private Location to;
    private boolean cancelled;

    public AtriumPreTeleportEvent(Player player, Location from, Location to, TeleportService.TeleportCause cause) {
        super(player);
        this.from = from.clone();
        this.to = to.clone();
        this.cause = cause;
    }

    /** Where the player is now (a copy). */
    public Location getFrom() {
        return from.clone();
    }

    /** The requested destination (a copy). */
    public Location getTo() {
        return to.clone();
    }

    public void setTo(Location to) {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(to.getWorld(), "destination world");
        this.to = to.clone();
    }

    public TeleportService.TeleportCause getCause() {
        return cause;
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
