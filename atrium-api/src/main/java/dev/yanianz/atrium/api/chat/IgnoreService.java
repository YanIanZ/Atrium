package dev.yanianz.atrium.api.chat;

import java.util.Set;
import java.util.UUID;

/**
 * Per-player ignore lists. Published by the {@code chat} module, which owns the data behind {@code /ignore}.
 *
 * <p>Implementations must be safe to call from any thread: chat is filtered inside
 * {@code AsyncChatEvent}, and mail delivery resolves this service from an asynchronous task. The returned
 * set is an immutable snapshot; never cache it across calls because the player may change their list.
 * Unknown or not-yet-loaded players are treated as having an empty list.
 */
public interface IgnoreService {
    /**
     * Whether {@code who} currently ignores {@code ignored}. Returns {@code false} for the same player or a
     * {@code null} argument, and never throws for a player Atrium does not know.
     */
    boolean isIgnoring(UUID who, UUID ignored);

    /** Immutable snapshot of the players {@code who} ignores, empty when unknown. */
    Set<UUID> ignored(UUID who);
}
