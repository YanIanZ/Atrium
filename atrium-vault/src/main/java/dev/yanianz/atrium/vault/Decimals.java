package dev.yanianz.atrium.vault;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * BigDecimal and double conversions for the v1/v2 bridges. Doubles coming from a v1 provider or a v1 caller are
 * approximations and are rounded (HALF_EVEN) to the economy's fractional digits; a BigDecimal amount going into a v1
 * provider must be exactly representable, otherwise the call is refused instead of silently moving a different amount.
 */
public final class Decimals {
    private Decimals() {
    }

    /**
     * A double (a v1 balance, or a v1 caller's amount) as a decimal, rounded HALF_EVEN to {@code digits} when
     * {@code digits >= 0}.
     */
    public static BigDecimal fromDouble(double value, int digits) {
        if (!Double.isFinite(value)) throw new ArithmeticException("Non-finite value " + value);
        BigDecimal decimal = BigDecimal.valueOf(value); // shortest decimal that round-trips: 0.1 -> 0.1
        return digits >= 0 ? decimal.setScale(digits, RoundingMode.HALF_EVEN) : decimal;
    }

    /**
     * Converts a v2 amount for a v1 provider, or returns {@code null} with no conversion possible: more decimal places
     * than the provider supports ({@code digits >= 0}), or a value a double cannot represent exactly
     * ({@code BigDecimal.valueOf(d)} must equal the amount).
     */
    public static Double toDouble(BigDecimal amount, int digits) {
        if (amount == null) return null;
        BigDecimal stripped = amount.signum() == 0 ? BigDecimal.ZERO : amount.stripTrailingZeros();
        if (digits >= 0 && stripped.scale() > digits) return null;
        double value = stripped.doubleValue();
        if (!Double.isFinite(value) || BigDecimal.valueOf(value).compareTo(stripped) != 0) return null;
        return value;
    }

    /** Why {@link #toDouble} refused {@code amount}. */
    public static String notRepresentable(BigDecimal amount, int digits) {
        if (amount == null) return "Amount must not be null";
        BigDecimal stripped = amount.signum() == 0 ? BigDecimal.ZERO : amount.stripTrailingZeros();
        if (digits >= 0 && stripped.scale() > digits) {
            return "Amount " + amount.toPlainString() + " has more decimal places than the economy supports (" + digits + ")";
        }
        return "Amount " + amount.toPlainString() + " cannot be passed exactly to a Vault v1 economy";
    }
}
