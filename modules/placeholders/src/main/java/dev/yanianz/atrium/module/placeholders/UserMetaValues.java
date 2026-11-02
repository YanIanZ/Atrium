package dev.yanianz.atrium.module.placeholders;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Rules for {@code /usermeta} keys and values. Keys become part of a placeholder name and a database key,
 * so they are restricted and lower-cased; increment keeps whole numbers whole and never uses a locale
 * decimal separator.
 */
final class UserMetaValues {
    /** Prefix of the placeholder after {@code atrium_}. */
    static final String PLACEHOLDER_PREFIX = "user_meta_";

    private static final Pattern KEY = Pattern.compile("[a-z0-9_.-]{1,32}");
    private static final int MAX_VALUE = 512;

    private UserMetaValues() {}

    static boolean handles(String placeholderKey) {
        return placeholderKey != null && placeholderKey.startsWith(PLACEHOLDER_PREFIX)
                && placeholderKey.length() > PLACEHOLDER_PREFIX.length();
    }

    /** The metadata key addressed by a placeholder, lower-cased, or empty when it is not valid. */
    static Optional<String> keyFromPlaceholder(String placeholderKey) {
        if (!handles(placeholderKey)) {
            return Optional.empty();
        }
        String key = placeholderKey.substring(PLACEHOLDER_PREFIX.length()).trim().toLowerCase(Locale.ROOT);
        return KEY.matcher(key).matches() ? Optional.of(key) : Optional.empty();
    }

    /** Normalises an operator-typed key; empty when it cannot be stored. */
    static Optional<String> normalizeKey(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        return KEY.matcher(key).matches() ? Optional.of(key) : Optional.empty();
    }

    /** Accepts a value up to 512 characters without control characters or line breaks. */
    static Optional<String> normalizeValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip();
        if (value.length() > MAX_VALUE) {
            return Optional.empty();
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || Character.isISOControl(c)) {
                return Optional.empty();
            }
        }
        return Optional.of(value);
    }

    /**
     * Adds two numeric strings. Whole numbers stay whole ({@code 2 + 3 = 5}); decimals use
     * {@link BigDecimal} and never lose precision to a locale. Empty when either side is not a number.
     */
    static Optional<String> increment(String current, String amount) {
        String left = current == null || current.isBlank() ? "0" : current.strip();
        String right = amount == null || amount.isBlank() ? "1" : amount.strip();
        try {
            BigDecimal sum = new BigDecimal(left).add(new BigDecimal(right));
            return Optional.of(format(sum));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String format(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            return stripped.toBigInteger().toString();
        }
        return stripped.toPlainString();
    }
}
