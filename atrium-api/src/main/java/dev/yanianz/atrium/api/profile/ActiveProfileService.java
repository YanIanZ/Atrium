package dev.yanianz.atrium.api.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The active profile of online players, as reported by the server's profile system (SourbyCraft AWF
 * MultiProfile). Published by the {@code sourbycraft} module only while MultiProfile is enabled.
 *
 * <p>{@link #cached} never blocks and is safe from any thread (placeholders, async chat). {@link #refresh}
 * asks the profile system; its future completes on no particular thread and must never be joined on a
 * tick thread.
 */
public interface ActiveProfileService {
    /** One profile as seen by one player. */
    record ActiveProfile(UUID id, String name, String type, String role, String status, UUID virtualId,
            UUID memberVirtualId) {}

    /** Whether the profile system is enabled and ready. */
    boolean enabled();

    /** Last known active profile of an online player; empty when unknown or the player has none. */
    Optional<ActiveProfile> cached(UUID player);

    /** Fetches the active profile and updates the cache. */
    CompletableFuture<Optional<ActiveProfile>> refresh(UUID player);

    /** Ids of the Atrium data that follows the active profile (config {@code profile-scoped}), in order. */
    List<String> scopedData();

    /** Human-readable description of how money relates to profiles (purse backend and Atrium economy). */
    String economyMode();
}
