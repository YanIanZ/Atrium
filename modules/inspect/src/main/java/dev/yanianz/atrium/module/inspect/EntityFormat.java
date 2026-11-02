package dev.yanianz.atrium.module.inspect;

import java.math.BigDecimal;
import java.util.Locale;

/** Small conversions for the entity diagnostics; pure and unit tested. */
final class EntityFormat {
    private EntityFormat() {}

    /**
     * Horizontal jump height in blocks of a horse-like entity from its jump strength, using the vanilla
     * relation {@code 5.293 * strength^1.7} (1.18+). Returns 0 for values outside the natural range instead
     * of a meaningless negative or infinite height.
     */
    static double jumpHeight(double strength) {
        if (!Double.isFinite(strength) || strength <= 0.0) {
            return 0.0;
        }
        double clamped = Math.min(1.0, strength);
        return 5.293 * Math.pow(clamped, 1.7);
    }

    static String blocks(double value) {
        if (!Double.isFinite(value)) {
            return "?";
        }
        return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString() + " blocks";
    }

    static String key(org.bukkit.Keyed keyed) {
        return keyed == null || keyed.getKey() == null ? "" : keyed.getKey().toString();
    }

    static String enumName(Enum<?> value) {
        return value == null ? "" : value.name().toLowerCase(Locale.ROOT);
    }
}
