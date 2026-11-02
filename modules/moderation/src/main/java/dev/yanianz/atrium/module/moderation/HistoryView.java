package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * How one history row is presented: its state, whether it can still be lifted, and the colour-coded
 * message keys. Pure so the formatting rules are covered by unit tests.
 */
final class HistoryView {
    private HistoryView() {}

    enum State {
        ACTIVE,
        EXPIRED,
        REVOKED,
        /** A kick: kept for the history, never "in effect". */
        INSTANT;

        String messageKey() {
            return "moderation.status." + name().toLowerCase(Locale.ROOT);
        }
    }

    static State state(Punishment punishment, Instant now) {
        if (punishment.isRevoked()) {
            return State.REVOKED;
        }
        if (punishment.type().isInstant()) {
            return State.INSTANT;
        }
        return punishment.isExpired(now) ? State.EXPIRED : State.ACTIVE;
    }

    /** Only records that are still in effect can be lifted (kicks and expired rows cannot). */
    static boolean revocable(Punishment punishment, Instant now) {
        return !punishment.type().isInstant() && punishment.isActive(now);
    }

    /** Message key of the colour-coded type label, e.g. {@code moderation.type.tempban}. */
    static String typeKey(PunishmentType type) {
        return "moderation.type." + type.name().toLowerCase(Locale.ROOT);
    }

    /**
     * The node that allows lifting a record of this kind: the same node as the dedicated command
     * ({@code /unban}, {@code /unmute}, {@code /unjail}, {@code /unbanip}, {@code /freeze},
     * {@code /delwarn}). Viewing the history must not be enough to lift a punishment, so
     * {@code /history revoke} checks this node. {@code null} for kinds that have no revoke command.
     */
    static @Nullable String revokePermission(PunishmentType type) {
        return switch (type) {
            case BAN, TEMPBAN -> "atrium.moderation.unban";
            case MUTE, TEMPMUTE, SHADOWMUTE -> "atrium.moderation.unmute";
            case JAIL -> "atrium.moderation.unjail";
            case IPBAN -> "atrium.moderation.unbanip";
            case FREEZE -> "atrium.moderation.freeze";
            case WARN -> "atrium.moderation.delwarn";
            case KICK -> null;
        };
    }
}
