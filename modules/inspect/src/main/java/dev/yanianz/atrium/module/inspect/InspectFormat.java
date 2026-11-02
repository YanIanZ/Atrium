package dev.yanianz.atrium.module.inspect;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Pure formatting used by the diagnostics commands (no server state, unit tested). */
final class InspectFormat {
    private InspectFormat() {}

    /** Removes the {@code minecraft:} namespace, keeping namespaced ids of other registries intact. */
    static String shortKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return key.startsWith("minecraft:") ? key.substring("minecraft:".length()) : key;
    }

    /**
     * Formats a {@link org.bukkit.block.data.BlockData#getAsString()} value for humans:
     * {@code minecraft:oak_stairs[facing=north,half=bottom]} becomes
     * {@code oak_stairs[facing=north, half=bottom]}.
     */
    static String blockData(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.trim();
        int open = text.indexOf('[');
        if (open < 0) {
            return shortKey(text);
        }
        int close = text.lastIndexOf(']');
        String type = shortKey(text.substring(0, open).trim());
        if (close <= open) {
            return type;
        }
        String properties = text.substring(open + 1, close);
        StringBuilder out = new StringBuilder(type).append('[');
        boolean first = true;
        for (String property : properties.split(",")) {
            String trimmed = property.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!first) {
                out.append(", ");
            }
            out.append(trimmed);
            first = false;
        }
        return out.append(']').toString();
    }

    /** Cuts untrusted text to a sane length, marking the cut so nobody mistakes it for the whole value. */
    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(0, max - 1)) + "\u2026";
    }

    /** Player-visible text of a component, never parsed as MiniMessage. */
    static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }

    static String number(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    static String percent(double fraction) {
        return number(Math.round(fraction * 1000.0) / 10.0);
    }

    /** Escapes a value for the SNBT-like diagnostic output. */
    static String snbtQuote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * SNBT-like summary of an item, used when the Paper API has no component string:
     * {@code {id:"minecraft:stone",count:1,components:{...}}}. Insertion order is preserved.
     */
    static String snbtItem(String id, int count, Map<String, String> components) {
        StringBuilder out = new StringBuilder();
        out.append("{id:").append(snbtQuote(id)).append(",count:").append(count);
        if (components != null && !components.isEmpty()) {
            out.append(",components:{");
            boolean first = true;
            for (Map.Entry<String, String> entry : components.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                out.append(snbtQuote(entry.getKey())).append(':').append(snbtQuote(entry.getValue()));
                first = false;
            }
            out.append('}');
        }
        return out.append('}').toString();
    }

    /** Bounded numeric address check for /checkaccount; never used for DNS lookups. */
    static boolean isAddress(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim();
        if (value.indexOf(':') >= 0) {
            if (value.length() > 45) {
                return false;
            }
            int colons = 0;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == ':') {
                    colons++;
                } else if (c != '.' && Character.digit(c, 16) < 0) {
                    return false;
                }
            }
            return colons >= 2;
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    /** Lower-cased, trimmed helper for permission/command/substring filters. */
    static String filter(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** Builds the date formatter; throws {@link IllegalArgumentException} for an invalid pattern or zone. */
    static DateTimeFormatter formatter(String pattern, String zone) {
        ZoneId id;
        try {
            id = zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone.trim());
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("Invalid time zone '" + zone + "'", e);
        }
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withZone(id);
    }

    static String absolute(Instant instant, DateTimeFormatter formatter) {
        return formatter.format(instant);
    }

    /** Time since {@code then}, never negative (clock skew between servers sharing a database). */
    static Duration since(Instant then, Instant now) {
        Duration duration = Duration.between(then, now);
        return duration.isNegative() ? Duration.ZERO : duration;
    }
}
