package dev.yanianz.atrium.api.moderation;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What to apply. Build with {@link #of(PunishmentType, UUID, String)} and the {@code with*} methods.
 *
 * @param target      account to punish; may be {@code null} only for an {@link PunishmentType#IPBAN} by address
 * @param targetName  name of the account (for messages and history)
 * @param ip          address or CIDR range for an IP ban; {@code null} to use the account's last known address
 * @param actor       staff member, {@code null} for the console or a plugin. Only a {@code null} actor may
 *                    punish players holding {@code atrium.moderation.exempt}.
 * @param actorName   name shown in messages and history
 * @param reason      free text; blank means "use the configured default reason"
 * @param duration    {@code null} for permanent; must be positive otherwise
 * @param silent      announce only to staff ({@code atrium.moderation.notify})
 * @param context     extra data, e.g. the jail name for {@link PunishmentType#JAIL}
 */
public record PunishmentRequest(
        PunishmentType type,
        @Nullable UUID target,
        @Nullable String targetName,
        @Nullable String ip,
        @Nullable UUID actor,
        String actorName,
        String reason,
        @Nullable Duration duration,
        boolean silent,
        @Nullable String context) {

    public static final String CONSOLE_NAME = "Console";

    public PunishmentRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(actorName, "actorName");
        reason = reason == null ? "" : reason;
        if (duration != null && (duration.isZero() || duration.isNegative())) {
            throw new IllegalArgumentException("duration must be positive: " + duration);
        }
        if (target == null && (type != PunishmentType.IPBAN || ip == null)) {
            throw new IllegalArgumentException("target is required for " + type);
        }
    }

    /** A request issued by the console, permanent, not silent, default reason. */
    public static PunishmentRequest of(PunishmentType type, UUID target, String targetName) {
        return new PunishmentRequest(type, target, targetName, null, null, CONSOLE_NAME, "", null, false, null);
    }

    public PunishmentRequest withActor(@Nullable UUID actor, String actorName) {
        return new PunishmentRequest(type, target, targetName, ip, actor, actorName, reason, duration, silent, context);
    }

    public PunishmentRequest withReason(String reason) {
        return new PunishmentRequest(type, target, targetName, ip, actor, actorName, reason, duration, silent, context);
    }

    public PunishmentRequest withDuration(@Nullable Duration duration) {
        return new PunishmentRequest(type, target, targetName, ip, actor, actorName, reason, duration, silent, context);
    }

    public PunishmentRequest withSilent(boolean silent) {
        return new PunishmentRequest(type, target, targetName, ip, actor, actorName, reason, duration, silent, context);
    }

    public PunishmentRequest withIp(@Nullable String ip) {
        return new PunishmentRequest(type, target, targetName, ip, actor, actorName, reason, duration, silent, context);
    }

    public PunishmentRequest withContext(@Nullable String context) {
        return new PunishmentRequest(type, target, targetName, ip, actor, actorName, reason, duration, silent, context);
    }
}
