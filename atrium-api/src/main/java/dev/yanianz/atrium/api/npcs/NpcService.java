package dev.yanianz.atrium.api.npcs;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Read access to the server NPCs managed by the {@code npcs} module. Thread-safe.
 *
 * <p>Atrium owns the definitions: every NPC is stored in Atrium's database and rendered by the configured
 * backend. Editing methods are deliberately not exposed; other modules should only observe and, if they need
 * custom behaviour, listen to {@link NpcInteractEvent}.
 */
public interface NpcService {
    /** Snapshots of all NPCs, sorted by name (case-insensitive). */
    Collection<NpcInfo> all();

    /** The NPC with that name (case-insensitive). */
    Optional<NpcInfo> get(String name);

    default boolean exists(String name) {
        return get(name).isPresent();
    }

    /** Immutable view of one stored NPC. Display names are trusted admin MiniMessage templates. */
    record NpcInfo(long id, String name, String displayName, StoredLocation location, String skin, NpcType type) {
        public NpcInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(location, "location");
            skin = skin == null ? "" : skin;
            type = type == null ? NpcType.AUTO : type;
        }
    }
}
