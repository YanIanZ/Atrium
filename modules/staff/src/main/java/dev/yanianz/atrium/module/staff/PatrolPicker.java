package dev.yanianz.atrium.module.staff;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Chooses the next player to visit during a staff patrol: the one that was visited least recently.
 *
 * <p>Never-visited players come before every visited one, so a fresh patrol walks the whole server before
 * repeating. Ties are broken by the candidate order (the caller passes names sorted), which keeps /patrol
 * predictable instead of random. Offline players are simply never candidates.
 */
final class PatrolPicker {
    private PatrolPicker() {}

    /**
     * @param candidates the players eligible for a visit, in a stable order
     * @param lastVisit  target id -> epoch millis of the last patrol visit
     */
    static Optional<UUID> next(List<UUID> candidates, Map<UUID, Long> lastVisit) {
        UUID best = null;
        long bestTime = Long.MIN_VALUE;
        for (UUID candidate : candidates) {
            Long visited = lastVisit.get(candidate);
            long time = visited == null ? Long.MIN_VALUE : visited;
            if (best == null || time < bestTime) {
                best = candidate;
                bestTime = time;
            }
        }
        return Optional.ofNullable(best);
    }
}
