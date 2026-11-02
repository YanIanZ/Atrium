package dev.yanianz.atrium.api.moderation;

import java.util.Optional;
import java.util.UUID;

/**
 * Fast, thread-safe mute lookups for chat-like features (chat, private messages, mail, /me, signs...).
 * Published by the {@code moderation} module.
 *
 * <p>Answers come from memory and cover every online player (loaded before login completes) and players
 * punished on this server recently. For an offline player that is not loaded, the methods answer "not
 * muted"; use {@link PunishmentService#active(UUID)} when an exact answer for offline players is needed.
 *
 * <p>Consumers must treat the two kinds differently:
 * <ul>
 *   <li>{@link #isMuted(UUID)}: block the message and tell the player.</li>
 *   <li>{@link #isShadowMuted(UUID)}: pretend the message was sent, but show it to the sender only. Do not
 *       forward it anywhere else (other servers, Discord, web chat, spies, logs visible to players).</li>
 * </ul>
 */
public interface MuteService {
    /** {@code true} if the player has an active normal mute (not a shadow mute). */
    boolean isMuted(UUID player);

    /** {@code true} if the player has an active shadow mute. */
    boolean isShadowMuted(UUID player);

    /** The active mute of either kind, or empty. */
    Optional<MuteInfo> muteInfo(UUID player);
}
