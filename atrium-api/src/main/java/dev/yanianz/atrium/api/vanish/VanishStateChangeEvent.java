package dev.yanianz.atrium.api.vanish;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;

/**
 * Fired on the player's own thread <em>before</em> a player is vanished or made visible again. While listeners
 * run, {@link VanishService#isVanished} still returns the old state: read the state that is about to apply from
 * {@link #isVanishing()} instead. Cancelling the event keeps the old state; nothing is changed or stored.
 *
 * <p>To act on the result (for example to hide a player on a web map), listen at {@code MONITOR} with
 * {@code ignoreCancelled = true} and use {@link #isVanishing()}.
 */
public class VanishStateChangeEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean vanishing;
    private final @Nullable CommandSender actor;
    private boolean cancelled;

    public VanishStateChangeEvent(Player player, boolean vanishing, @Nullable CommandSender actor) {
        super(Objects.requireNonNull(player, "player"));
        this.vanishing = vanishing;
        this.actor = actor;
    }

    /** The new state: {@code true} when the player is about to vanish, {@code false} when about to reappear. */
    public boolean isVanishing() {
        return vanishing;
    }

    /** Who requested the change (the player, staff or the console); empty for plugins and automatic changes. */
    public Optional<CommandSender> getActor() {
        return Optional.ofNullable(actor);
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
