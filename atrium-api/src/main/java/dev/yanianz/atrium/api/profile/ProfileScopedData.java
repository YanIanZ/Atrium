package dev.yanianz.atrium.api.profile;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Per-player data of one module that can follow a player's active profile (SourbyCraft AWF
 * MultiProfile, or any other profile system that captures and restores player state).
 *
 * <p>Both methods are called on the player's owning thread while the profile system swaps state, so they
 * must be cheap and work only on data already loaded in memory: never wait for storage, never block.
 *
 * <ul>
 *   <li>{@link #capture} returns the current state as bytes (or {@code null} for "empty").</li>
 *   <li>{@link #apply} replaces the state <em>immediately</em> in memory (so the next command already sees
 *       the new profile's data) and starts persisting it; the returned future completes when it is stored.
 *       {@code null} means "the profile has no state for this id": the player starts empty, nothing is
 *       inherited from the previous profile.</li>
 * </ul>
 *
 * <p>Register with {@link ProfileScopedDataRegistry}; modules use {@code ModuleContext#profileScoped}.
 */
public interface ProfileScopedData {
    /** Stable id used in config ({@code profile-scoped}) and in stored snapshots: {@code [a-z0-9_-]{1,32}}. */
    String id();

    /** One line for {@code /atrium profile}. */
    default String description() {
        return id();
    }

    /** Format version of the bytes; bump when the format changes. Newer stored versions are refused. */
    default int version() {
        return 1;
    }

    /** The player's current state, or {@code null} when it is empty. Synchronous, from memory only. */
    byte @Nullable [] capture(UUID player) throws Exception;

    /**
     * Replaces the player's state with {@code data} ({@code null} = empty). The in-memory value changes
     * before this method returns; the future completes once the new value is stored.
     */
    CompletableFuture<Void> apply(UUID player, byte @Nullable [] data) throws Exception;
}
