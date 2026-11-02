package dev.yanianz.atrium.module.teleport;

import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/** Pure launchpad rules: which plate type launches with which power. */
final class Launchpads {
    private Launchpads() {}

    /** Upward velocity configured for {@code materialName}, empty when the block is not a launchpad. */
    static OptionalDouble power(Map<String, Double> pads, String materialName) {
        if (pads == null || pads.isEmpty() || materialName == null) {
            return OptionalDouble.empty();
        }
        String wanted = materialName.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Double> entry : pads.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (entry.getKey().trim().toUpperCase(Locale.ROOT).equals(wanted)) {
                double value = entry.getValue();
                return value > 0 && Double.isFinite(value) ? OptionalDouble.of(value) : OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }
}
