package dev.yanianz.atrium.api.playtime;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Administrative write access to playtime, published by the {@code playtime} module.
 *
 * <p>It exists for import and maintenance tools. Durations are handled with second resolution;
 * a negative duration is rejected with a future that fails with {@link IllegalArgumentException}.
 * Every call completes only after storage was written. For an online player the live session counter
 * is updated on the player's own thread as well, so the next periodic save continues from the new value
 * instead of overwriting it.
 */
public interface PlaytimeAdminService {
    /**
     * Replaces the player's total playtime. The previous daily buckets are removed and the new total
     * is stored as one bucket for today.
     */
    CompletableFuture<Void> setPlaytime(UUID player, Duration total);

    /** Adds to the player's total playtime (today's bucket). */
    CompletableFuture<Void> addPlaytime(UUID player, Duration amount);
}
