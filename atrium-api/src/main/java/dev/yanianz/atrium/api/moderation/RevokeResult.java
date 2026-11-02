package dev.yanianz.atrium.api.moderation;

import java.util.List;

/** Outcome of lifting punishments early (unban, unmute, unjail, delwarn, ...). */
public record RevokeResult(Status status, List<Punishment> revoked) {
    public enum Status {
        /** At least one record was revoked; {@link #revoked()} lists them (already marked revoked). */
        REVOKED,
        /** Nothing matching was active. */
        NOTHING_ACTIVE,
        /** A {@link PunishmentRevokeEvent} listener cancelled it; nothing changed. */
        CANCELLED,
        /** Storage failed; nothing changed. */
        FAILED
    }

    public RevokeResult {
        revoked = List.copyOf(revoked);
    }

    public static RevokeResult of(Status status) {
        return new RevokeResult(status, List.of());
    }

    public boolean success() {
        return status == Status.REVOKED;
    }
}
