package dev.yanianz.atrium.module.npcs;

import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * Pure part of {@code /npc select}: the exact entity ray trace is preferred, the view cone is only a
 * fallback. A hit is rejected when its key no longer maps to a stored NPC (a stale PDC marker) or when
 * the distance is outside the configured range, so a deleted NPC can never be selected through a ghost hit.
 */
final class NpcRay {
    private NpcRay() {}

    /** One candidate: distance from the player's eyes and the NPC key the hit resolved to. */
    record Hit(double distance, @Nullable String npcKey) {}

    static @Nullable String pick(@Nullable Hit exact, @Nullable Hit fallback, double maxDistance,
            Predicate<String> known) {
        String traced = accepted(exact, maxDistance, known);
        return traced != null ? traced : accepted(fallback, maxDistance, known);
    }

    private static @Nullable String accepted(@Nullable Hit hit, double maxDistance, Predicate<String> known) {
        if (hit == null || hit.npcKey() == null || !known.test(hit.npcKey())) {
            return null;
        }
        double distance = hit.distance();
        if (!Double.isFinite(distance) || distance < 0 || distance > maxDistance) {
            return null;
        }
        return hit.npcKey();
    }
}
