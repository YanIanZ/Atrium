package dev.yanianz.atrium.api.kits;

import java.math.BigDecimal;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Called on the receiving player's thread before a kit is reserved, charged and given. Cancelling it stops
 * the claim before any cost, cooldown or one-time use is consumed.
 */
public final class KitClaimEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();
    private final String kit;
    private final BigDecimal cost;
    private final boolean firstJoin;
    private boolean cancelled;

    public KitClaimEvent(Player player, String kit, BigDecimal cost, boolean firstJoin) {
        super(player);
        this.kit = kit;
        this.cost = cost;
        this.firstJoin = firstJoin;
    }

    /** Lower-case kit name. */
    public String kit() {
        return kit;
    }

    /** Amount that will be charged (zero when free or bypassed). */
    public BigDecimal cost() {
        return cost;
    }

    /** True when the kit is given automatically on a first join. */
    public boolean isFirstJoin() {
        return firstJoin;
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
