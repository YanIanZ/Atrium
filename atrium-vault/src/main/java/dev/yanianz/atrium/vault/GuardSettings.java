package dev.yanianz.atrium.vault;

/**
 * Immutable guard configuration.
 *
 * @param maxAmount largest single amount accepted for a write (inclusive); must be finite and positive
 */
public record GuardSettings(double maxAmount) {
    public static final double DEFAULT_MAX_AMOUNT = 1.0E12;

    public GuardSettings {
        if (!Double.isFinite(maxAmount) || maxAmount <= 0) {
            throw new IllegalArgumentException("guard.max-amount must be a finite positive number, got " + maxAmount);
        }
    }

    public static GuardSettings defaults() {
        return new GuardSettings(DEFAULT_MAX_AMOUNT);
    }
}
