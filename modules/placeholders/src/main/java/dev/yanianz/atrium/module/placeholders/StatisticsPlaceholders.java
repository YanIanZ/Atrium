package dev.yanianz.atrium.module.placeholders;

import dev.yanianz.atrium.api.statistics.StatisticsCatalog;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves {@code %atrium_stat_<name>%} and {@code %atrium_stat_<name>_formatted%} from the cache filled on
 * the player's own thread. Pure; a request never touches the server registry.
 */
final class StatisticsPlaceholders {
    private StatisticsPlaceholders() {}

    static boolean handles(String key) {
        return key != null && (key.startsWith("stat_") || key.startsWith("statistic_"));
    }

    /** @return the value, or the configured fallback when the statistic or its cached value is unknown */
    static String resolve(String key, Map<String, Long> values, String fallback) {
        if (values == null || values.isEmpty()) {
            return fallback;
        }
        String normalized = StatisticsCatalog.normalize(key);
        boolean formatted = normalized.endsWith("_formatted");
        if (formatted) {
            normalized = normalized.substring(0, normalized.length() - "_formatted".length());
        }
        Optional<StatisticsCatalog.Entry> entry = StatisticsCatalog.byKey(normalized);
        if (entry.isEmpty()) {
            return fallback;
        }
        Long raw = values.get(entry.get().key());
        if (raw == null) {
            return fallback;
        }
        return formatted ? StatisticsCatalog.format(entry.get(), raw) : Long.toString(raw);
    }
}
