package dev.yanianz.atrium.api.worldrules;

import java.util.Objects;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockEvent;

/**
 * Fired on the block's region thread before {@code /blockcycle} changes a block state. Cancelling it
 * prevents the change. Protection plugins can use this to deny state changes in claims just like they
 * deny block placement.
 */
public final class BlockCycleEvent extends BlockEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final BlockData oldData;
    private final BlockData newData;
    private boolean cancelled;

    public BlockCycleEvent(Player player, Block block, BlockData oldData, BlockData newData) {
        super(block);
        this.player = Objects.requireNonNull(player, "player");
        this.oldData = Objects.requireNonNull(oldData, "oldData");
        this.newData = Objects.requireNonNull(newData, "newData");
    }

    public Player player() {
        return player;
    }

    /** The state before cycling. */
    public BlockData oldData() {
        return oldData;
    }

    /** The state that will be written when the event is not cancelled. */
    public BlockData newData() {
        return newData;
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
