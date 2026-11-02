package dev.yanianz.atrium.api.teleport;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Adds entries to a player's {@code /back} history from outside the teleport module, for import tools.
 * Published by the {@code teleport} module. Works for offline players and players who never joined.
 */
public interface BackHistoryAdminService {
    /**
     * Adds {@code location} as the newest {@code /back} entry of {@code player}, unless the newest entry
     * already is that exact location.
     *
     * @param recordedAt epoch millis of the original event; entries older than the configured /back
     *                   lifetime may be dropped by the teleport module when the player uses /back
     * @return {@code true} when the entry was stored, {@code false} when it was already the newest entry.
     *         Completes only after the write was stored.
     */
    CompletableFuture<Boolean> addBackLocation(UUID player, StoredLocation location, long recordedAt);
}
