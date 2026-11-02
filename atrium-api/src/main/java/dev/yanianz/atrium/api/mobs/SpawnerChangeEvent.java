package dev.yanianz.atrium.api.mobs;

import java.util.Objects;
import org.bukkit.block.Block;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jspecify.annotations.Nullable;

/**
 * Fired on the spawner's region thread before {@code /setspawner} changes the spawned type of a spawner.
 * Cancelling it leaves the spawner unchanged.
 */
public final class SpawnerChangeEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Block block;
    private final @Nullable EntityType previous;
    private final EntityType type;
    private boolean cancelled;

    public SpawnerChangeEvent(Player player, Block block, @Nullable EntityType previous, EntityType type) {
        super(player);
        this.block = Objects.requireNonNull(block, "block");
        this.previous = previous;
        this.type = Objects.requireNonNull(type, "type");
    }

    public Block block() {
        return block;
    }

    /** The type spawned before the change, or {@code null} for an empty spawner. */
    public @Nullable EntityType previous() {
        return previous;
    }

    public EntityType type() {
        return type;
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
