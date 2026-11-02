package dev.yanianz.atrium.module.economy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Tax applied to /pay. A flat component and a percentage component are added; when a maximum above zero
 * is configured the result is clamped to {@code [minimum, maximum]} (both are then also rounded to the
 * economy scale).
 *
 * <p>Two models exist and both keep the receiver's money exact:
 * <ul>
 *   <li>{@code deductOnTop = false}: the sender pays {@code amount}, the receiver gets
 *       {@code amount - tax}; the tax disappears into the sink,</li>
 *   <li>{@code deductOnTop = true}: the sender pays {@code amount + tax}, the receiver gets
 *       {@code amount}.</li>
 * </ul>
 */
public record PayTax(BigDecimal flat, BigDecimal percent, BigDecimal minimum, BigDecimal maximum,
        boolean deductOnTop) {
    public static final PayTax NONE = new PayTax(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, false);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public PayTax {
        flat = nonNegative(flat);
        percent = nonNegative(percent);
        minimum = nonNegative(minimum);
        maximum = nonNegative(maximum);
    }

    public boolean enabled() {
        return flat.signum() > 0 || percent.signum() > 0;
    }

    /** Tax owed for a payment of {@code amount}, rounded with the economy scale and rounding mode. */
    public BigDecimal tax(BigDecimal amount, int scale, RoundingMode rounding) {
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Payment amount must not be negative");
        }
        if (scale < 0 || scale > 10 || rounding == null) {
            throw new IllegalArgumentException("Invalid economy rounding");
        }
        BigDecimal value = flat.add(amount.multiply(percent).divide(HUNDRED, MathContext.DECIMAL128));
        if (maximum.signum() > 0) {
            if (value.compareTo(minimum) < 0) {
                value = minimum;
            }
            if (value.compareTo(maximum) > 0) {
                value = maximum;
            }
        }
        if (value.signum() < 0) {
            value = BigDecimal.ZERO;
        }
        return value.setScale(scale, rounding);
    }

    /** What leaves the sender's account for a command amount of {@code amount}. */
    public BigDecimal senderPays(BigDecimal amount, BigDecimal tax) {
        return deductOnTop ? amount.add(tax) : amount;
    }

    /** What the receiver's account gains for a command amount of {@code amount}. */
    public BigDecimal receiverGets(BigDecimal amount, BigDecimal tax) {
        return deductOnTop ? amount : amount.subtract(tax);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException("Tax components must not be negative");
        }
        return value;
    }
}
