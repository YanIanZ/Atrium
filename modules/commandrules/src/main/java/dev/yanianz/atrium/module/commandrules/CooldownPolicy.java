package dev.yanianz.atrium.module.commandrules;

import java.time.Duration;
import java.util.OptionalInt;

/** Pure cooldown arithmetic, including the per-player numeric permission override. */
public final class CooldownPolicy {
    /** A {@code -1} cooldown is stored as a far-future expiry, so it survives restarts like any other. */
    public static final Duration LIFETIME = Duration.ofDays(365L * 100L);

    private CooldownPolicy() {}

    /**
     * Effective cooldown in seconds. A configured {@code 0} means "no cooldown" and is never overridden.
     * Otherwise the player's highest {@code atrium.cmdcooldown.<command>.<seconds>} permission replaces
     * the configured value; it may raise or lower it, and {@code 0} disables the cooldown for that player.
     */
    public static int effectiveSeconds(int configured, OptionalInt permissionOverride) {
        if (configured == 0) {
            return 0;
        }
        if (permissionOverride.isPresent()) {
            return Math.max(0, permissionOverride.getAsInt());
        }
        return configured;
    }

    /** Duration for an effective value; {@code -1} is a lifetime cooldown. */
    public static Duration duration(int seconds) {
        if (seconds == -1) {
            return LIFETIME;
        }
        return seconds <= 0 ? Duration.ZERO : Duration.ofSeconds(seconds);
    }

    /** Human-readable configured cooldown, used by /commandrules. */
    public static String display(int seconds, String none, String lifetime) {
        if (seconds == 0) {
            return none;
        }
        if (seconds == -1) {
            return lifetime;
        }
        return seconds + "s";
    }
}
