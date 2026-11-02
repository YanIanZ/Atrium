package dev.yanianz.atrium.module.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Exact boundary parsing; never passes a monetary value through floating point. */
final class Money {
    static final BigDecimal STORAGE_MAX = new BigDecimal("99999999999999999999.9999999999");
    final int scale;
    final RoundingMode rounding;
    final BigDecimal maxAmount;
    private final String symbol;
    private final String template;

    Money(int scale, RoundingMode rounding, BigDecimal maxAmount, String symbol, String template) {
        if (scale < 0 || scale > 10 || maxAmount.signum() < 0 || maxAmount.compareTo(STORAGE_MAX) > 0) {
            throw new IllegalArgumentException("Invalid economy scale or maximum amount");
        }
        this.scale = scale;
        this.rounding = rounding;
        this.maxAmount = maxAmount;
        this.symbol = symbol;
        this.template = template;
    }

    BigDecimal parse(String text) {
        if (text == null || text.length() > 96 || !text.matches("[0-9]+(?:\\.[0-9]+)?[kKmMbB]?")) {
            throw new IllegalArgumentException("Invalid amount");
        }
        char suffix = Character.toLowerCase(text.charAt(text.length() - 1));
        int exponent = switch (suffix) { case 'k' -> 3; case 'm' -> 6; case 'b' -> 9; default -> 0; };
        String digits = exponent == 0 ? text : text.substring(0, text.length() - 1);
        return amount(new BigDecimal(digits).movePointRight(exponent));
    }

    BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.compareTo(maxAmount) > 0
                || value.scale() > 1000 || value.scale() < -1000 || value.precision() > 1000) {
            throw new IllegalArgumentException("Amount outside allowed range");
        }
        BigDecimal normalized = value.setScale(scale, rounding);
        if (normalized.compareTo(maxAmount) > 0 || normalized.compareTo(STORAGE_MAX) > 0) {
            throw new IllegalArgumentException("Rounded amount outside allowed range");
        }
        return normalized;
    }

    String format(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0" + (scale == 0 ? "" : "." + "0".repeat(scale)),
                DecimalFormatSymbols.getInstance(Locale.ROOT));
        format.setRoundingMode(rounding);
        return template.replace("{symbol}", symbol).replace("{amount}", format.format(amount));
    }
}
