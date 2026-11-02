package dev.yanianz.atrium.module.effects;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Pure cooldown decision shared by the persisted cooldown check and the tests.
 *
 * @param allowed   true when the player may trigger the effect now
 * @param remaining time left when {@code allowed} is false, otherwise {@link Duration#ZERO}
 */
public record CooldownDecision(boolean allowed, Duration remaining) {
    public CooldownDecision {
        if (remaining == null || remaining.isNegative()) {
            remaining = Duration.ZERO;
        }
        if (allowed) {
            remaining = Duration.ZERO;
        }
    }

    public static CooldownDecision permit() {
        return new CooldownDecision(true, Duration.ZERO);
    }

    public static CooldownDecision blocked(Duration remaining) {
        return new CooldownDecision(false, remaining);
    }

    /**
     * Decides from a stored expiry.
     *
     * @param nowMillis      current epoch millis
     * @param expiresMillis  stored expiry, or {@code null} when the player never used the effect
     * @param cooldownMillis configured cooldown; zero or negative disables the check
     */
    public static CooldownDecision decide(long nowMillis, @Nullable Long expiresMillis, long cooldownMillis) {
        if (cooldownMillis <= 0 || expiresMillis == null) {
            return permit();
        }
        long left = expiresMillis - nowMillis;
        return left > 0 ? blocked(Duration.ofMillis(left)) : permit();
    }
}
