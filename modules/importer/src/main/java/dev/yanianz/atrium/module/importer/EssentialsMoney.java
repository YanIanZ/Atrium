package dev.yanianz.atrium.module.importer;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses an EssentialsX balance. EssentialsX writes a plain YAML number, but old files and hand edits
 * contain currency symbols, thousands separators or a quoted decimal, so those are accepted too. The
 * value keeps its own scale: the economy module rounds to its configured scale when it stores it.
 *
 * <p>Comma rules (a comma is never just dropped, or {@code "12,50"} would become 1250):
 * <ul>
 *   <li>the only separator, used once and followed by 1-2 digits: decimal separator ({@code 12,50} = 12.50);</li>
 *   <li>otherwise a thousands separator, which must group exactly three digits before any decimal point
 *       ({@code 1,234}, {@code 1,234,567.89});</li>
 *   <li>anything else ({@code 1.234,56}, {@code 12,5000}, {@code 1,23,456}) is ambiguous and rejected, so it
 *       ends up in the report instead of being imported as a guess.</li>
 * </ul>
 */
final class EssentialsMoney {
    private static final BigDecimal MAX = new BigDecimal("1000000000000000");
    private static final int MAX_SCALE = 10;
    private static final Pattern DECIMAL_COMMA = Pattern.compile("\\d+,\\d{1,2}");
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(,\\d{3})+(\\.\\d+)?");

    private EssentialsMoney() {}

    static Optional<BigDecimal> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("nan") || lower.contains("infinity")) {
            return Optional.empty();
        }
        text = text.replace("$", "").replace("€", "").replace("£", "").replace("_", "").trim();
        if (text.startsWith("+")) {
            text = text.substring(1);
        }
        if (text.indexOf(',') >= 0) {
            String normalized = normalizeCommas(text);
            if (normalized == null) {
                return Optional.empty();
            }
            text = normalized;
        }
        try {
            BigDecimal value = new BigDecimal(text);
            if (value.signum() < 0 || value.compareTo(MAX) > 0 || value.scale() > MAX_SCALE) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** Applies the comma rules of the class comment; {@code null} when the value is ambiguous. */
    static String normalizeCommas(String text) {
        if (text.indexOf('.') < 0 && DECIMAL_COMMA.matcher(text).matches()) {
            return text.replace(',', '.');
        }
        if (THOUSANDS.matcher(text).matches()) {
            return text.replace(",", "");
        }
        return null;
    }
}
