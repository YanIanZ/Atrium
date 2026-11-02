package dev.yanianz.atrium.module.performance;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Warning marks that fit inside a countdown, announced longest first. Pure and unit-tested. */
final class GroundCleanCountdown {
    private GroundCleanCountdown() {}

    static List<Integer> warnings(int leadSeconds, Collection<Integer> configured) {
        if (leadSeconds <= 0 || configured == null || configured.isEmpty()) {
            return List.of();
        }
        return configured.stream()
                .filter(value -> value != null && value > 0 && value <= leadSeconds)
                .distinct()
                .sorted(Comparator.reverseOrder())
                .toList();
    }
}
