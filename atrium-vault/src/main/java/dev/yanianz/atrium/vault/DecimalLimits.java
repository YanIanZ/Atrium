package dev.yanianz.atrium.vault;

import java.math.BigDecimal;

/**
 * Limits the v2 guard applies to {@link BigDecimal} amounts.
 *
 * @param maxAmount    largest single amount accepted for a write (inclusive); positive
 * @param maxScale     most decimal places an amount may carry, ignoring trailing zeros (0-64)
 * @param maxPrecision most significant digits an amount may carry (1-1000)
 */
public record DecimalLimits(BigDecimal maxAmount, int maxScale, int maxPrecision) {
    public static final int DEFAULT_MAX_SCALE = 10;
    public static final int DEFAULT_MAX_PRECISION = 38;
    /** Reads (has, canWithdraw, ...) have no maximum, but an amount with more integer digits than this is nonsense. */
    static final int MAX_INTEGER_DIGITS = 40;

    public DecimalLimits {
        if (maxAmount == null || maxAmount.signum() <= 0) {
            throw new IllegalArgumentException("guard.max-amount must be a positive number, got " + maxAmount);
        }
        if (maxScale < 0 || maxScale > 64) {
            throw new IllegalArgumentException("guard.max-scale must be between 0 and 64, got " + maxScale);
        }
        if (maxPrecision < 1 || maxPrecision > 1000) {
            throw new IllegalArgumentException("guard.max-precision must be between 1 and 1000, got " + maxPrecision);
        }
    }

    public static DecimalLimits of(GuardSettings settings) {
        return new DecimalLimits(BigDecimal.valueOf(settings.maxAmount()), DEFAULT_MAX_SCALE, DEFAULT_MAX_PRECISION);
    }

    /**
     * Returns why {@code amount} is not acceptable, or {@code null}. Trailing zeros do not count towards the scale, so
     * {@code 1.500000000000} is a valid amount with scale 1. {@code write} also applies {@link #maxAmount()}.
     */
    public String check(BigDecimal amount, boolean write) {
        if (amount == null) return "Amount must not be null";
        if (amount.signum() < 0) return "Amount must not be negative";
        if (amount.precision() > maxPrecision) return "Amount has more than " + maxPrecision + " significant digits";
        if (amount.signum() == 0) return null;
        BigDecimal stripped = amount.stripTrailingZeros();
        if (stripped.scale() > maxScale) return "Amount has more than " + maxScale + " decimal places";
        if (write && amount.compareTo(maxAmount) > 0) {
            return "Amount exceeds the configured maximum of " + maxAmount.toPlainString();
        }
        if (stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) return "Amount is too large";
        return null;
    }
}
