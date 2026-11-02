package dev.yanianz.atrium.api.moderation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One row of the punishment history. Immutable.
 *
 * <p>Whether a punishment is in effect is decided only by comparing times ({@link #isActive(Instant)}):
 * nothing polls for expiry, so there is no window in which an expired punishment is still enforced
 * or a fresh one is not yet enforced.
 *
 * @param id            storage id (shown to staff, used by {@code /delwarn})
 * @param target        punished account; {@code null} for a pure IP ban
 * @param targetName    last known name of the account at the time of the punishment
 * @param ip            IP address or CIDR range for {@link PunishmentType#IPBAN}, else {@code null}
 * @param actor         staff member; {@code null} for the console or a plugin
 * @param actorName     display name of the actor ("Console" for the console)
 * @param expires       {@code null} for permanent punishments
 * @param revokedBy     who lifted it early; {@code null} if not revoked or revoked by the console
 * @param revokedAt     when it was lifted early; {@code null} while not revoked
 * @param context       extra data depending on the kind (the jail name for {@link PunishmentType#JAIL})
 */
public record Punishment(
        long id,
        PunishmentType type,
        @Nullable UUID target,
        @Nullable String targetName,
        @Nullable String ip,
        @Nullable UUID actor,
        String actorName,
        String reason,
        Instant created,
        @Nullable Instant expires,
        @Nullable UUID revokedBy,
        @Nullable String revokedByName,
        @Nullable Instant revokedAt,
        @Nullable String context) {

    public Punishment {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actorName, "actorName");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(created, "created");
    }

    public boolean isPermanent() {
        return expires == null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expires != null && !expires.isAfter(now);
    }

    /** In effect at {@code now}: not revoked, not expired and not an instant kind (kick). */
    public boolean isActive(Instant now) {
        return !type.isInstant() && !isRevoked() && !isExpired(now);
    }

    /** Time left, or empty for permanent punishments. Never negative. */
    public Optional<Duration> remaining(Instant now) {
        if (expires == null) {
            return Optional.empty();
        }
        Duration left = Duration.between(now, expires);
        return Optional.of(left.isNegative() ? Duration.ZERO : left);
    }

    /** Copy marked as revoked. */
    public Punishment revoked(@Nullable UUID by, String byName, Instant at) {
        return new Punishment(id, type, target, targetName, ip, actor, actorName, reason, created, expires, by, byName, at,
                context);
    }
}
