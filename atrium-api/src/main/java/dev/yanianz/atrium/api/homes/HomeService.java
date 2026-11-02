package dev.yanianz.atrium.api.homes;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Administrative home access published by the {@code homes} module.
 *
 * <p>It deliberately bypasses home limits and the normal command permissions: it exists for import and
 * maintenance tools, not for player-facing features. Normal players keep using {@code /home} and
 * {@code /sethome}. Implementations are thread-safe and every write completes only after it was stored.
 */
public interface HomeService {
    /** All homes of a player, in stored order. Completes with an empty list when none are stored. */
    CompletableFuture<List<HomeInfo>> homes(UUID player);

    /**
     * Replaces every home of a player in one transaction. An empty list removes all homes. The whole
     * call fails with {@link Result#INVALID} and stores nothing when any name or location is invalid.
     */
    CompletableFuture<Result> replaceHomes(UUID player, List<HomeInfo> homes);

    /** Removes one home by name (case-insensitive). */
    CompletableFuture<Result> removeHome(UUID player, String name);

    enum Result {
        SUCCESS,
        /** Nothing to remove. */
        SKIPPED,
        /** Invalid name or location; nothing was written. */
        INVALID
    }

    /** One home as stored by the homes module. */
    record HomeInfo(String name, StoredLocation location, long createdAt) {
        public HomeInfo {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(location, "location");
        }
    }
}
