package dev.yanianz.atrium.module.search;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Deterministic "top N" aggregation for the entity scan. Ties are broken by key so the output does
 * not shuffle between runs. Pure logic, unit tested.
 */
public final class TopN {
    public record Row(String key, int count) {}

    private TopN() {}

    public static List<Row> top(Map<String, Integer> counts, int limit) {
        if (limit <= 0 || counts.isEmpty()) {
            return List.of();
        }
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0)
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(limit)
                .map(entry -> new Row(entry.getKey(), entry.getValue()))
                .toList();
    }
}
