package dev.yanianz.atrium.module.player;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Validated command values for the extended player utilities. */
final class UtilityValues {
    static final double MAX_FALL_DISTANCE = 10_000.0;
    static final double MAX_SOUND_VOLUME = 10.0;
    static final double MAX_SHOOT_SPEED = 5.0;
    static final int MAX_EXPRESSION_LENGTH = 256;

    /** Projectiles offered by /shoot; any other spawnable projectile is accepted at runtime too. */
    static final List<String> PROJECTILES = List.of(
            "arrow", "spectral_arrow", "trident", "snowball", "egg", "ender_pearl",
            "small_fireball", "fireball", "dragon_fireball", "wither_skull", "shulker_bullet",
            "llama_spit", "splash_potion", "lingering_potion", "experience_bottle", "wind_charge",
            "breeze_wind_charge", "fishing_bobber");

    private static final Set<String> VALID_NAMESPACES = Set.of("minecraft");

    private UtilityValues() { }

    /** A validated sound argument, kept as typed so suggestions and errors stay predictable. */
    record SoundInput(String value, boolean namespaced) { }

    static SoundInput sound(String input) {
        String value = input == null ? "" : input.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Sound must not be empty");
        }
        String lower = value.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        if (colon >= 0) {
            String namespace = lower.substring(0, colon);
            String path = lower.substring(colon + 1);
            if (!VALID_NAMESPACES.contains(namespace) || !path.matches("[a-z0-9_./-]+")) {
                throw new IllegalArgumentException("Invalid sound key");
            }
            return new SoundInput(lower, true);
        }
        // An upper-case constant (BLOCK_NOTE_BLOCK_PLING) is an enum name; anything else that looks like
        // a resource path (block.note_block.pling) is treated as minecraft:<path>.
        if (value.matches("[A-Z][A-Z0-9_]*")) {
            return new SoundInput(value, false);
        }
        if (lower.matches("[a-z0-9_./-]+")) {
            return new SoundInput("minecraft:" + lower, true);
        }
        throw new IllegalArgumentException("Invalid sound name");
    }

    /** Rounds to ten decimals and drops trailing zeros, so 2+2 reads as 4 rather than 4.0000000000. */
    static String number(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Result is not finite");
        }
        String text = String.format(Locale.ROOT, "%.10f", value);
        int dot = text.indexOf('.');
        if (dot >= 0) {
            int end = text.length();
            while (end > dot && text.charAt(end - 1) == '0') {
                end--;
            }
            if (end > dot && text.charAt(end - 1) == '.') {
                end--;
            }
            text = text.substring(0, end);
        }
        return text.equals("-0") ? "0" : text;
    }

    /** Validates a value used by /falldistance. */
    static float fallDistance(double value) {
        if (!Double.isFinite(value) || value < 0 || value > MAX_FALL_DISTANCE) {
            throw new IllegalArgumentException("Fall distance must be 0-" + (int) MAX_FALL_DISTANCE);
        }
        return (float) value;
    }

    /** Validates a /shoot speed. */
    static double shootSpeed(double speed) {
        if (!Double.isFinite(speed) || speed < 0 || speed > MAX_SHOOT_SPEED) {
            throw new IllegalArgumentException("Shoot speed must be 0-" + (int) MAX_SHOOT_SPEED);
        }
        return speed;
    }

    /** Plain, locale-independent comparison key for material whitelists. */
    static String materialKey(String key) {
        return key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
    }

    /** Formats a configurable duration for messages. */
    static String seconds(long seconds) {
        return BigDecimal.valueOf(Math.max(0L, seconds)).toPlainString();
    }
}
