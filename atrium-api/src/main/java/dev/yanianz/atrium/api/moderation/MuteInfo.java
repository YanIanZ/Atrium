package dev.yanianz.atrium.api.moderation;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * An active mute.
 *
 * @param shadow  {@code true} for a shadow mute: the player must not be told, and their messages must be
 *                shown to themselves only (and must not be relayed to other servers, Discord, web maps...)
 * @param expires {@code null} when permanent
 */
public record MuteInfo(
        UUID player,
        long punishmentId,
        @Nullable UUID actor,
        String actorName,
        String reason,
        Instant created,
        @Nullable Instant expires,
        boolean shadow) {

    public static MuteInfo of(Punishment punishment) {
        if (!PunishmentType.MUTES.contains(punishment.type()) || punishment.target() == null) {
            throw new IllegalArgumentException("Not a player mute: " + punishment.type());
        }
        return new MuteInfo(punishment.target(), punishment.id(), punishment.actor(), punishment.actorName(),
                punishment.reason(), punishment.created(), punishment.expires(),
                punishment.type() == PunishmentType.SHADOWMUTE);
    }
}
