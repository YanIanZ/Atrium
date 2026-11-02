package dev.yanianz.atrium.api.warps;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Optional;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.Nullable;

/**
 * Called before a warp is created, moved or renamed. Nothing is changed or stored until the event
 * passed; when it is cancelled the existing warps stay exactly as they were (a cancelled rename keeps
 * the old warp).
 */
public class WarpCreateEvent extends Event implements Cancellable {
    private static final HandlerList HANDLERS = new HandlerList();

    private final CommandSender actor;
    private final String name;
    private final @Nullable String renamedFrom;
    private final StoredLocation location;
    private final String category;
    private boolean cancelled;

    public WarpCreateEvent(CommandSender actor, String name, @Nullable String renamedFrom, StoredLocation location,
            String category) {
        this.actor = actor;
        this.name = name;
        this.renamedFrom = renamedFrom;
        this.location = location;
        this.category = category;
    }

    public CommandSender getActor() {
        return actor;
    }

    /** The (new) warp name. */
    public String getName() {
        return name;
    }

    /** The old name when this is a rename. */
    public Optional<String> getRenamedFrom() {
        return Optional.ofNullable(renamedFrom);
    }

    public StoredLocation getLocation() {
        return location;
    }

    public String getCategory() {
        return category;
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
