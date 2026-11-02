package dev.yanianz.atrium.api.moderation;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

/**
 * Import entry point of the {@code moderation} module: stores jails and punishment records taken from
 * another plugin. Unlike {@link PunishmentService#punish}, an imported punishment fires no event, sends no
 * announcement and is not enforced live; it is enforced like any stored record (at login, on chat, ...).
 */
public interface ModerationImportService {
    /**
     * A jail. {@code release} is where released players go when their own pre-jail location is unknown,
     * or {@code null} for the configured fallback.
     */
    record JailImport(String name, StoredLocation location, double radius, @Nullable StoredLocation release) {
        public JailImport {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(location, "location");
        }
    }

    /**
     * A punishment record. {@code target} may be {@code null} only for {@link PunishmentType#IPBAN};
     * {@code expires == null} means permanent. {@code context} is the jail name for
     * {@link PunishmentType#JAIL} ({@code null} for the default jail).
     */
    record PunishmentImport(PunishmentType type, @Nullable UUID target, @Nullable String targetName,
            @Nullable String ip, String actorName, String reason, Instant created, @Nullable Instant expires,
            @Nullable String context) {
        public PunishmentImport {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(created, "created");
            actorName = actorName == null || actorName.isBlank() ? PunishmentRequest.CONSOLE_NAME : actorName;
            reason = reason == null ? "" : reason;
        }
    }

    enum Status {
        /** Stored. */
        IMPORTED,
        /** An active record of the same group (or an active ban of the same address) exists; nothing stored. */
        DUPLICATE,
        /** Already expired; nothing stored. */
        EXPIRED,
        /** A jail punishment whose jail does not exist; nothing stored. */
        NO_JAIL,
        /** Kind not importable (kick, warn, freeze, shadow mute) or malformed (e.g. a bad IP); nothing stored. */
        INVALID
    }

    /** Names of the existing jails. */
    Collection<String> jailNames();

    /**
     * Creates the jail, or replaces its location, radius and release point when {@code overwrite} is true
     * (its other options are kept). Invalid names or radii fail with {@link IllegalArgumentException}.
     *
     * @return {@code true} when written, {@code false} when it exists and {@code overwrite} is false
     */
    CompletableFuture<Boolean> importJail(JailImport jail, boolean overwrite);

    /** Stores one punishment record (see {@link Status}). */
    CompletableFuture<Status> importPunishment(PunishmentImport punishment);
}
