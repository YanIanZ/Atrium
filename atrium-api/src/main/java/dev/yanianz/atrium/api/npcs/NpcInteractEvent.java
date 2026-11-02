package dev.yanianz.atrium.api.npcs;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Fired on the player's own thread when a player clicks an Atrium NPC, before Atrium checks the NPC
 * permission, cooldown or cost and before any action line runs.
 *
 * <p>Cancel it to stop the click completely: no permission feedback, no cooldown, no cost and no action.
 * Protection plugins can use this to deny NPC use in claims.
 */
public final class NpcInteractEvent extends PlayerEvent implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    /** Which mouse button was used. */
    public enum Click {
        LEFT,
        RIGHT
    }

    private final String npc;
    private final Click click;
    private final NpcType type;
    private boolean cancelled;

    public NpcInteractEvent(Player player, String npc, Click click, NpcType type) {
        super(player);
        this.npc = Objects.requireNonNull(npc, "npc");
        this.click = Objects.requireNonNull(click, "click");
        this.type = type == null ? NpcType.NATIVE : type;
    }

    /** The NPC name in its stored spelling. */
    public String getNpc() {
        return npc;
    }

    public Click getClick() {
        return click;
    }

    public NpcType getType() {
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
