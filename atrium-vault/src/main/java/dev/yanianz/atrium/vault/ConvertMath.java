package dev.yanianz.atrium.vault;

import java.math.BigDecimal;

/** Delta arithmetic for {@code /vault-convert}: how to move a target balance onto a source balance. */
public final class ConvertMath {
    private ConvertMath() {
    }

    public enum Action { NONE, DEPOSIT, WITHDRAW, INVALID }

    /** What to do with the target account; {@code amount} is non-negative. */
    public record Plan(Action action, double amount) {
    }

    /** {@link #plan(double, double, int)} for an economy without rounding (tiny relative tolerance). */
    public static Plan plan(double source, double target) {
        return plan(source, target, -1);
    }

    /**
     * Computes the deposit or withdrawal that makes {@code target} equal to {@code source}. The difference is taken in
     * decimal (via {@link BigDecimal#valueOf(double)}) so that, e.g., 10.1 - 0.3 is exactly 9.8. Balances that already
     * {@linkplain #matches match} within the target's precision need no write.
     */
    public static Plan plan(double source, double target, int fractionalDigits) {
        if (!Double.isFinite(source) || !Double.isFinite(target)) return new Plan(Action.INVALID, 0);
        if (matches(source, target, fractionalDigits)) return new Plan(Action.NONE, 0);
        BigDecimal delta = BigDecimal.valueOf(source).subtract(BigDecimal.valueOf(target));
        int sign = delta.signum();
        if (sign == 0) return new Plan(Action.NONE, 0);
        double amount = delta.abs().doubleValue();
        return new Plan(sign > 0 ? Action.DEPOSIT : Action.WITHDRAW, amount);
    }

    /**
     * True when {@code actual} matches {@code expected} within half a unit of the target economy's last fractional
     * digit ({@code fractionalDigits < 0} means "no rounding": a tiny relative tolerance is used).
     */
    public static boolean matches(double expected, double actual, int fractionalDigits) {
        if (!Double.isFinite(expected) || !Double.isFinite(actual)) return false;
        double tolerance = fractionalDigits >= 0 ? 0.5 * Math.pow(10, -fractionalDigits)
                : 1e-9 * Math.max(1, Math.abs(expected));
        return Math.abs(expected - actual) <= tolerance + Math.ulp(expected);
    }
}
