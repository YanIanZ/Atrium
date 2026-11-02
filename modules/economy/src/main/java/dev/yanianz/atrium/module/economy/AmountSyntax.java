package dev.yanianz.atrium.module.economy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.random.RandomGenerator;

/**
 * Amount expressions accepted by /pay and /eco, in addition to a plain decimal with an optional
 * k/m/b suffix:
 *
 * <ul>
 *   <li>{@code 25%} — 25 percent of the base balance (the payer for /pay, the target for /eco),</li>
 *   <li>{@code rand:10-250} — a random amount between both bounds,</li>
 *   <li>{@code rand:5-15%} — a random percentage between both bounds.</li>
 * </ul>
 *
 * <p>Parsing is exact and locale independent. Only the random component uses a {@link RandomGenerator};
 * the result is then rounded by {@link Money} with the configured economy rounding mode, so no monetary
 * value is ever persisted as a floating point number.
 */
public final class AmountSyntax {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100000000");

    /** A parsed expression. {@link #needsBase()} marks expressions that need a balance to resolve. */
    public sealed interface Expression permits Fixed, Percent, Random, RandomPercent {
        boolean needsBase();
    }

    /** A fixed amount, already normalized to the configured scale by the caller. */
    public record Fixed(BigDecimal value) implements Expression {
        @Override public boolean needsBase() { return false; }
    }

    /** A percentage of the base balance. */
    public record Percent(BigDecimal percent) implements Expression {
        @Override public boolean needsBase() { return true; }
    }

    /** A uniform random amount in {@code [min, max]}. */
    public record Random(BigDecimal min, BigDecimal max) implements Expression {
        @Override public boolean needsBase() { return false; }
    }

    /** A uniform random percentage of the base balance in {@code [min, max]}. */
    public record RandomPercent(BigDecimal min, BigDecimal max) implements Expression {
        @Override public boolean needsBase() { return true; }
    }

    private AmountSyntax() {}

    /**
     * @throws IllegalArgumentException when the text is not a supported expression
     */
    public static Expression parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("Amount is missing");
        }
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > 96) {
            throw new IllegalArgumentException("Invalid amount length");
        }
        // Accept the historic %rand:a-b% / %rand/a-b% spelling as well as rand:a-b.
        if (value.length() > 2 && value.startsWith("%") && value.endsWith("%")) {
            value = value.substring(1, value.length() - 1);
        }
        value = value.replace("rand/", "rand:");
        if (value.startsWith("rand:")) {
            String range = value.substring("rand:".length());
            boolean percent = range.endsWith("%");
            if (percent) {
                range = range.substring(0, range.length() - 1);
            }
            int dash = range.indexOf('-');
            if (dash <= 0 || dash == range.length() - 1 || range.indexOf('-', dash + 1) >= 0) {
                throw new IllegalArgumentException("Invalid random range");
            }
            BigDecimal min = number(range.substring(0, dash));
            BigDecimal max = number(range.substring(dash + 1));
            if (!percent && min.compareTo(max) > 0) {
                throw new IllegalArgumentException("Random range is reversed");
            }
            if (percent && max.compareTo(MAX_PERCENT) > 0) {
                throw new IllegalArgumentException("Percent out of range");
            }
            return percent ? new RandomPercent(min, max) : new Random(min, max);
        }
        if (value.endsWith("%")) {
            BigDecimal percent = number(value.substring(0, value.length() - 1));
            if (percent.compareTo(MAX_PERCENT) > 0) {
                throw new IllegalArgumentException("Percent out of range");
            }
            return new Percent(percent);
        }
        return new Fixed(number(value));
    }

    /**
     * Resolves the expression with {@code scale} decimal places, rounding down so the result never
     * exceeds what the player asked for (a 10% share of 99.99 is 9.99, never 10.00).
     *
     * @param base balance used by percentage expressions; ignored by fixed and random amounts
     * @throws IllegalArgumentException when a percentage expression has no valid base
     */
    public static BigDecimal resolve(Expression expression, BigDecimal base, int scale, RandomGenerator random) {
        if (expression == null) {
            throw new IllegalArgumentException("Amount is missing");
        }
        if (scale < 0 || scale > 10) {
            throw new IllegalArgumentException("Invalid scale");
        }
        BigDecimal raw = switch (expression) {
            case Fixed fixed -> fixed.value();
            case Percent percent -> percentOf(base, percent.percent());
            case Random random1 -> between(random1.min(), random1.max(), random);
            case RandomPercent percent -> percentOf(base, between(percent.min(), percent.max(), random));
        };
        if (raw.signum() < 0) {
            throw new IllegalArgumentException("Negative amount");
        }
        return raw.setScale(scale, RoundingMode.DOWN);
    }

    private static BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
        if (base == null || base.signum() < 0) {
            throw new IllegalArgumentException("Percentage needs a balance");
        }
        return base.multiply(percent).divide(HUNDRED, MathContext.DECIMAL128);
    }

    /** Uniform value in {@code [min, max]}; the top bound is inclusive at the configured scale. */
    private static BigDecimal between(BigDecimal min, BigDecimal max, RandomGenerator random) {
        if (min.signum() < 0 || max.compareTo(min) < 0) {
            throw new IllegalArgumentException("Invalid random range");
        }
        if (min.compareTo(max) == 0) {
            return min;
        }
        BigDecimal spread = max.subtract(min);
        BigDecimal unit = BigDecimal.valueOf(random.nextDouble());
        return min.add(spread.multiply(unit, MathContext.DECIMAL128));
    }

    /** Plain decimal with an optional k/m/b suffix; same syntax as {@link Money#parse}. */
    private static BigDecimal number(String text) {
        if (text.isEmpty() || !text.matches("[0-9]+(?:\\.[0-9]+)?[kmb]?")) {
            throw new IllegalArgumentException("Invalid number");
        }
        char suffix = text.charAt(text.length() - 1);
        int exponent = switch (suffix) {
            case 'k' -> 3;
            case 'm' -> 6;
            case 'b' -> 9;
            default -> 0;
        };
        String digits = exponent == 0 ? text : text.substring(0, text.length() - 1);
        if (digits.isEmpty()) {
            throw new IllegalArgumentException("Invalid number");
        }
        return new BigDecimal(digits).movePointRight(exponent);
    }
}
