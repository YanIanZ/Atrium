package dev.yanianz.atrium.api.teleport;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Administrative access to the stored spawn points, published by the {@code teleport} module. Meant for
 * import and maintenance tools; {@code /setspawn} keeps its own permission checks.
 *
 * <p>The world of a location does not have to be loaded: spawns are stored by world name. A write
 * completes only after it was stored.
 */
public interface SpawnAdminService {
    /** Which spawn point. {@link #WORLD} needs a world name; the others ignore it. */
    enum Kind { DEFAULT, FIRST_JOIN, WORLD }

    /** The stored spawn of that kind, without any fallback; empty when none was set. */
    Optional<StoredLocation> spawn(Kind kind, @Nullable String world);

    /** Stores a spawn point, replacing the previous one of the same kind (and world). */
    CompletableFuture<Void> setSpawn(Kind kind, @Nullable String world, StoredLocation location);

    /** {@code true} when {@code /spawn} uses per-world spawns ({@link Kind#WORLD}) before the default one. */
    boolean perWorldSpawns();

    /**
     * Group spawns (EssentialsSpawn style) by lower-case group name. {@code /spawn} uses the group spawn of a
     * player who has {@code atrium.spawn.group.<group>} set, before the default spawn. Empty when the
     * implementation has no group spawns.
     */
    default Map<String, StoredLocation> groupSpawns() {
        return Map.of();
    }

    /** Stores the spawn of one group (the name is lower-cased), replacing the previous one. */
    default CompletableFuture<Void> setGroupSpawn(String group, StoredLocation location) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("group spawns are not supported"));
    }

    /** Per-world spawns ({@link Kind#WORLD}) by world name. */
    default Map<String, StoredLocation> worldSpawns() {
        return Map.of();
    }
}
