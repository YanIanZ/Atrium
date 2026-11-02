package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import java.time.Instant;
import java.util.List;

/**
 * Decides whether a player whose jail sentence ran out must be released. Expiry is a time comparison:
 * nothing polls, and an expired record is only recognised when the player joins or moves.
 */
final class JailRules {
    private JailRules() {}

    /**
     * @param release   {@code true} when an expired jail exists and no newer jail is still in effect
     * @param expired   ids that can be dropped from the in-memory copy
     */
    record Decision(boolean release, List<Long> expired) {
        Decision {
            expired = List.copyOf(expired);
        }

        static final Decision NONE = new Decision(false, List.of());
    }

    static Decision decide(List<Punishment> records, Instant now) {
        return decide(records, now, false);
    }

    /**
     * @param releaseMarker {@code true} when a release location was stored and not consumed yet; together
     *                      with no active jail this means the sentence ended while the player was offline
     */
    static Decision decide(List<Punishment> records, Instant now, boolean releaseMarker) {
        List<Long> expired = records.stream()
                .filter(p -> p.type() == PunishmentType.JAIL && p.isExpired(now))
                .map(Punishment::id)
                .toList();
        boolean stillJailed = records.stream()
                .anyMatch(p -> p.type() == PunishmentType.JAIL && p.isActive(now));
        boolean release = !stillJailed && (releaseMarker || !expired.isEmpty());
        return release || !expired.isEmpty() ? new Decision(release, expired) : Decision.NONE;
    }
}
