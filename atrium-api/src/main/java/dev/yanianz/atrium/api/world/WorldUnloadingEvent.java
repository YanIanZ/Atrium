package dev.yanianz.atrium.api.world;

import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired by Atrium when it learns that a world is being unloaded through a path that does not fire
 * Bukkit's {@link org.bukkit.event.world.WorldUnloadEvent} first (for example SourbyCraft's asynchronous
 * unload). Not cancellable: listeners pause or drop state tied to the world (pending teleports into it,
 * caches). Listen to both this event and {@code WorldUnloadEvent}; handlers must be idempotent.
 *
 * <p>Only the world name is given: by the time a listener runs the world may already be gone.
 */
public final class WorldUnloadingEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    private final String worldName;
    private final String source;

    public WorldUnloadingEvent(String worldName, String source) {
        super(!Bukkit.isPrimaryThread());
        this.worldName = Objects.requireNonNull(worldName, "worldName");
        this.source = Objects.requireNonNull(source, "source");
    }

    public String worldName() {
        return worldName;
    }

    /** Who reported the unload, for logs (for example {@code "sourbycraft:WorldUnloadAsyncEvent"}). */
    public String source() {
        return source;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
