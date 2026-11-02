package dev.yanianz.atrium.api.portals;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Called on the player's own thread when a player enters an Atrium portal region and is about to be sent
 * to its target (and its action line run). Nothing happens yet: when the event is cancelled the player is
 * not teleported and no action runs. The re-entry cooldown has already been started at this point, so a
 * cancelled use is not retried on every block the player moves.
 */
public class PortalUseEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    /** What the portal does. */
    public enum TargetType {
        /** No teleport; only the action line (if any) runs. */
        NONE,
        /** Teleport to a warp of the warps module; {@link #getTargetValue()} is the warp name. */
        WARP,
        /** Teleport to a fixed location; {@link #getTargetValue()} is the encoded location. */
        LOCATION,
        /** Send to another server behind a proxy; {@link #getTargetValue()} is the server name. */
        SERVER
    }

    private final String portal;
    private final TargetType targetType;
    private final String targetValue;
    private boolean cancelled;

    public PortalUseEvent(Player player, String portal, TargetType targetType, String targetValue) {
        super(player);
        this.portal = Objects.requireNonNull(portal, "portal");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.targetValue = targetValue == null ? "" : targetValue;
    }

    /** The portal name in its stored spelling. */
    public String getPortal() {
        return portal;
    }

    public TargetType getTargetType() {
        return targetType;
    }

    /** Warp name, server name or encoded location; empty for {@link TargetType#NONE}. */
    public String getTargetValue() {
        return targetValue;
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
