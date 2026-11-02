package dev.yanianz.atrium.module.worldrules;

import java.util.Locale;
import java.util.Map;

/** Pure per-world rule math and value parsing; no Bukkit server access, so it is unit-testable. */
final class WorldRules {
    private WorldRules() {}

    /** Exact world name first, then the {@code "*"} entry, then vanilla defaults. */
    static WorldRule resolve(Map<String, WorldRule> worlds, String worldName) {
        if (worlds == null || worlds.isEmpty()) {
            return WorldRule.NONE;
        }
        WorldRule exact = worldName == null ? null : worlds.get(worldName);
        if (exact != null) {
            return exact;
        }
        WorldRule fallback = worlds.get("*");
        return fallback == null ? WorldRule.NONE : fallback;
    }

    /** Fall damage after the multiplier; 0 means the damage must be cancelled. */
    static double fallDamage(double damage, WorldRule rule) {
        if (!Double.isFinite(damage) || damage <= 0) {
            return damage;
        }
        if (rule.disableFallDamage) {
            return 0;
        }
        return damage * rule.fallDamageMultiplier();
    }

    /**
     * The new food level and the fractional drain carried to the next event. A multiplier below 1 would
     * otherwise round every one-point drain back to one; the carry makes a 0.5 multiplier drop a point
     * every second event instead.
     */
    static HungerResult hunger(int current, int requested, WorldRule rule, double carry) {
        if (requested >= current) {
            // Eating, regeneration and saturation never get multiplied or blocked.
            return new HungerResult(requested, 0);
        }
        if (rule.disableHunger) {
            return new HungerResult(current, 0);
        }
        double factor = rule.hungerMultiplier();
        if (factor <= 0) {
            return new HungerResult(current, 0);
        }
        if (factor == 1.0) {
            return new HungerResult(requested, 0);
        }
        double exact = (current - requested) * factor + cleanCarry(carry);
        int drain = (int) Math.floor(exact);
        if (drain <= 0) {
            return new HungerResult(current, exact);
        }
        int next = Math.max(0, current - drain);
        return new HungerResult(next, exact - drain);
    }

    private static double cleanCarry(double carry) {
        return Double.isFinite(carry) && carry > 0 ? Math.min(carry, 1000.0) : 0;
    }

    record HungerResult(int foodLevel, double carry) {}

    /** A parsed {@code world, x, y, z[, yaw, pitch]} gateway target. */
    record GatewayTarget(String world, double x, double y, double z, float yaw, float pitch) {}

    /** Returns {@code null} for blank or malformed input; never returns a target with a non-finite number. */
    static GatewayTarget parseGatewayTarget(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String[] parts = text.split(",", -1);
        if (parts.length < 4 || parts.length > 6) {
            return null;
        }
        String world = parts[0].trim();
        if (world.isEmpty()) {
            return null;
        }
        double x = number(parts[1]);
        double y = number(parts[2]);
        double z = number(parts[3]);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return null;
        }
        float yaw = 0;
        float pitch = 0;
        if (parts.length >= 5) {
            double parsed = number(parts[4]);
            if (!Double.isFinite(parsed)) {
                return null;
            }
            yaw = (float) parsed;
        }
        if (parts.length >= 6) {
            double parsed = number(parts[5]);
            if (!Double.isFinite(parsed)) {
                return null;
            }
            pitch = (float) parsed;
        }
        return new GatewayTarget(world, x, y, z, yaw, pitch);
    }

    private static double number(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** Lower-case name used in messages; keeps unknown values visible for the admin. */
    static String describe(String value) {
        return value == null || value.isBlank() ? "off" : value.toLowerCase(Locale.ROOT);
    }
}
