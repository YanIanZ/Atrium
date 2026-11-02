package dev.yanianz.atrium.api.afk;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * AFK state published by the {@code afk} module.
 *
 * <p>Only online players can be AFK; the state is intentionally transient and is not restored after a
 * restart. All methods are safe to call from any thread.
 */
public interface AfkService {
    /** True while the player is marked AFK. */
    boolean isAfk(UUID player);

    /** When the current AFK session started, or empty when the player is not AFK. */
    Optional<Instant> afkSince(UUID player);

    /**
     * Changes the AFK state of an online player. {@link AfkStateChangeEvent} is fired before anything is
     * applied; when a listener cancels it this returns {@code false} and the state is unchanged.
     *
     * <p>When called from another thread the change is scheduled on the player's own thread and this
     * method returns {@code true} to mean "accepted". A player who is already in the requested state is
     * left alone and this returns {@code true}.
     */
    boolean setAfk(Player player, boolean afk, AfkReason reason);
}
