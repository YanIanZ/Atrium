package dev.yanianz.atrium.module.importer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses the CMI {@code Economy} column. CMI stores a map world-group to amount either as the legacy
 * {@code group%%value;group%%value} text or as JSON. The default group wins; otherwise the first numeric
 * entry is used. Negative, non-finite and unreasonably large values are rejected.
 */
final class CmiBalanceParser {
    /** 1 quadrillion: far above any legitimate balance and safely exact in a double. */
    private static final BigDecimal MAX = new BigDecimal("1000000000000000");
    private static final BigDecimal MIN = BigDecimal.ZERO;

    private CmiBalanceParser() {}

    static Optional<BigDecimal> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        if (text.startsWith("{")) {
            return parseJson(text);
        }
        if (text.indexOf("%%") >= 0 || text.indexOf(';') >= 0) {
            return parseLegacyMap(text);
        }
        return parsePlain(text);
    }

    private static Optional<BigDecimal> parseJson(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                BigDecimal preferred = numeric(object.get("default"));
                if (preferred == null) {
                    preferred = numeric(object.get("Default"));
                }
                if (preferred != null) {
                    return validate(preferred);
                }
                for (var entry : object.entrySet()) {
                    BigDecimal value = numeric(entry.getValue());
                    if (value != null) {
                        return validate(value);
                    }
                }
                return Optional.empty();
            }
            if (element.isJsonPrimitive()) {
                return parsePlain(element.getAsString());
            }
            if (element.isJsonArray()) {
                for (JsonElement item : element.getAsJsonArray()) {
                    BigDecimal value = numeric(item);
                    if (value != null) {
                        return validate(value);
                    }
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> parseLegacyMap(String text) {
        BigDecimal first = null;
        for (String entry : text.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            int split = entry.indexOf("%%");
            String key = split < 0 ? "" : entry.substring(0, split).trim();
            String value = split < 0 ? entry.trim() : entry.substring(split + 2).trim();
            Optional<BigDecimal> parsed = parsePlain(value);
            if (parsed.isEmpty()) {
                continue;
            }
            if (key.equalsIgnoreCase("default")) {
                return parsed;
            }
            if (first == null) {
                first = parsed.get();
            }
        }
        return first == null ? Optional.empty() : validate(first);
    }

    /** Parses one number, accepting thousands separators and a currency prefix. */
    static Optional<BigDecimal> parsePlain(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.trim().replace("$", "").replace("€", "").replace(",", "").replace("_", "");
        if (text.startsWith("+")) {
            text = text.substring(1);
        }
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return validate(new BigDecimal(text));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> validate(BigDecimal value) {
        if (value.compareTo(MIN) < 0 || value.compareTo(MAX) > 0) {
            return Optional.empty();
        }
        return Optional.of(value.setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal numeric(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            String text = element.getAsString().trim().toLowerCase(Locale.ROOT);
            if (text.equals("nan") || text.contains("infinity")) {
                return null;
            }
            return new BigDecimal(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
