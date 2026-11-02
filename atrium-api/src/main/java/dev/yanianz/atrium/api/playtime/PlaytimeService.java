package dev.yanianz.atrium.api.playtime;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read-only view of the playtime tracked by the {@code playtime} module.
 *
 * <p>Online players are answered from memory, so their total includes the current session even
 * before the next periodic flush. Offline players are read from storage.
 */
public interface PlaytimeService {
    /**
     * Total active playtime in seconds. Completes with {@code 0} for a player who has never been
     * tracked. The future fails when storage is unavailable.
     */
    CompletableFuture<Long> totalPlaytime(UUID player);

    /**
     * Seconds counted in the current session, or {@code 0} when the player is not online (or their
     * session is still loading). This value is not persisted yet; use {@link #totalPlaytime(UUID)}
     * for the authoritative total.
     */
    long sessionTime(UUID player);
}
