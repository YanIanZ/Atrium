package dev.yanianz.atrium.api.armorstand;

import java.util.Objects;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired on the player's (and the armor stand's) region thread before Atrium's armor stand editor changes
 * a stand. Cancelling it prevents the change. Protection plugins can use this to deny edits in claims.
 */
public final class ArmorStandEditEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    /** What is about to change. */
    public enum Action {
        /** The editor GUI is being opened. */
        OPEN,
        /** A boolean property (arms, base plate, small, gravity, visible, glowing, invulnerable, name visible). */
        TOGGLE,
        /** A body part pose changes. */
        POSE,
        /** A saved or copied pose (and flags) is applied as a whole. */
        APPLY_POSE,
        /** An equipment slot is swapped with the player's main-hand item. */
        EQUIPMENT
    }

    private final ArmorStand stand;
    private final Action action;
    private final String detail;
    private boolean cancelled;

    public ArmorStandEditEvent(Player player, ArmorStand stand, Action action, String detail) {
        super(player);
        this.stand = Objects.requireNonNull(stand, "stand");
        this.action = Objects.requireNonNull(action, "action");
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    public ArmorStand stand() {
        return stand;
    }

    public Action action() {
        return action;
    }

    /** Lower-case detail, e.g. the toggle or part name ({@code "glowing"}, {@code "left_arm"}); may be empty. */
    public String detail() {
        return detail;
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
