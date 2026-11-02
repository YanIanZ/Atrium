package dev.yanianz.atrium.api.moderation;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outcome of {@link PunishmentService#punish(PunishmentRequest)}. */
public record PunishResult(Status status, @Nullable Punishment punishment) {
    public enum Status {
        /** Stored and enforced. {@link #punishment()} is the new record. */
        APPLIED,
        /** A {@link PunishmentEvent} listener cancelled it; nothing was stored. */
        CANCELLED,
        /** Target holds {@code atrium.moderation.exempt} and the actor is not the console. */
        EXEMPT,
        /** IP ban requested for an account whose address is unknown. */
        NO_KNOWN_IP,
        /** Jail requested but the jail does not exist (or no jail is set up). */
        NO_JAIL,
        /** Invalid request (e.g. kick of an offline player). */
        INVALID,
        /** Storage failed; nothing was enforced. */
        FAILED
    }

    public boolean applied() {
        return status == Status.APPLIED;
    }

    public Optional<Punishment> punishmentOptional() {
        return Optional.ofNullable(punishment);
    }

    public static PunishResult of(Status status) {
        return new PunishResult(status, null);
    }
}
