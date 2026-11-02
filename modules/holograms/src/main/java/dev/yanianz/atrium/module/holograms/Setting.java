package dev.yanianz.atrium.module.holograms;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

/**
 * One per-hologram option, changed with {@code /holo setting <name> <option> <value>} and stored as
 * {@code key=value} lines. Parsing and formatting are locale-independent, so a stored hologram reads back
 * identically on any JVM locale.
 */
enum Setting {
    BILLBOARD("billboard", "CENTER") {
        @Override Object parse(String raw) {
            return parseEnum(Display.Billboard.class, raw);
        }

        @Override List<String> suggestions() {
            return names(Display.Billboard.values());
        }
    },
    SCALE("scale", "1.0") {
        @Override Object parse(String raw) {
            return parseFloat(raw, 0.05f, 20f);
        }
    },
    /** {@code default} (vanilla translucent grey), {@code none} or {@code #RRGGBB}/{@code #AARRGGBB}. */
    BACKGROUND("background", "default") {
        @Override Object parse(String raw) {
            return parseBackground(raw);
        }

        @Override List<String> suggestions() {
            return List.of("default", "none", "#40000000", "#FF000000");
        }
    },
    SHADOW("shadow", "false") {
        @Override Object parse(String raw) {
            return parseBoolean(raw);
        }
    },
    SEE_THROUGH("see-through", "false") {
        @Override Object parse(String raw) {
            return parseBoolean(raw);
        }
    },
    /** Gap in blocks between stacked elements (text blocks, items, blocks), scaled with {@link #SCALE}. */
    LINE_SPACING("line-spacing", "0.02") {
        @Override Object parse(String raw) {
            return parseFloat(raw, -2f, 5f);
        }
    },
    ALIGNMENT("alignment", "CENTER") {
        @Override Object parse(String raw) {
            return parseEnum(TextDisplay.TextAlignment.class, raw);
        }

        @Override List<String> suggestions() {
            return names(TextDisplay.TextAlignment.values());
        }
    },
    /** Blocks. Converted to the client's view-range multiplier (blocks / 64) when applied. */
    VIEW_RANGE("view-range", "48.0") {
        @Override Object parse(String raw) {
            return parseFloat(raw, 1f, 256f);
        }
    },
    /** Text wrap width in text pixels (vanilla default 200). */
    LINE_WIDTH("line-width", "200") {
        @Override Object parse(String raw) {
            return parseInt(raw, 10, 4000);
        }
    },
    /** Ticks between refreshes of text with placeholders; 0 never refreshes. */
    UPDATE_INTERVAL("update-interval", "40") {
        @Override Object parse(String raw) {
            return parseInt(raw, 0, 72_000);
        }
    },
    /** Ticks between automatic page switches; 0 disables the timer. */
    PAGE_INTERVAL("page-interval", "0") {
        @Override Object parse(String raw) {
            return parseInt(raw, 0, 72_000);
        }
    },
    /** Permission needed to see (and click) the hologram; {@code none} = everyone. */
    PERMISSION("permission", "") {
        @Override Object parse(String raw) {
            return parsePermission(raw);
        }

        @Override List<String> suggestions() {
            return List.of("none", "atrium.holograms.see.");
        }
    },
    /** Extra permission needed to click; {@code none} = everyone who can see it. */
    CLICK_PERMISSION("click-permission", "") {
        @Override Object parse(String raw) {
            return parsePermission(raw);
        }

        @Override List<String> suggestions() {
            return List.of("none", "atrium.holograms.use.");
        }
    },
    /** Milliseconds between two clicks of one player on this hologram; 0 = no cooldown. */
    CLICK_COOLDOWN("click-cooldown", "500") {
        @Override Object parse(String raw) {
            return parseInt(raw, 0, 86_400_000);
        }
    },
    /** Separate per-viewer entities: AUTO when the text has placeholders, ON always, OFF never. */
    PER_VIEWER("per-viewer", "AUTO") {
        @Override Object parse(String raw) {
            return parseEnum(PerViewer.class, raw);
        }

        @Override List<String> suggestions() {
            return names(PerViewer.values());
        }
    },
    /** Without click actions, right click shows the next page and left click the previous one. */
    CLICK_PAGES("click-pages", "true") {
        @Override Object parse(String raw) {
            return parseBoolean(raw);
        }
    },
    /** Click box width in blocks; 0 sizes it from the text bounds. */
    INTERACTION_WIDTH("interaction-width", "0.0") {
        @Override Object parse(String raw) {
            return parseFloat(raw, 0f, 32f);
        }
    },
    /** Click box height in blocks; 0 sizes it from the text bounds. */
    INTERACTION_HEIGHT("interaction-height", "0.0") {
        @Override Object parse(String raw) {
            return parseFloat(raw, 0f, 32f);
        }
    };

    enum PerViewer { AUTO, ON, OFF }

    private static final Pattern PERMISSION_NODE = Pattern.compile("[a-z0-9_.\\-]{1,128}");
    private static final Pattern HEX = Pattern.compile("#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})");

    final String key;
    final String defaultValue;

    Setting(String key, String defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    /** Parses and normalises a value. Throws {@link IllegalArgumentException} with a readable reason. */
    abstract Object parse(String raw);

    List<String> suggestions() {
        Object def = parse(defaultValue);
        if (def instanceof Boolean) {
            return List.of("true", "false");
        }
        return List.of(format(def));
    }

    /** Locale-independent text form; {@code parse(format(v))} returns {@code v}. */
    String format(Object value) {
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Float f) {
            return Float.toString(f);
        }
        return String.valueOf(value);
    }

    static Optional<Setting> byKey(String key) {
        String wanted = key.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values()).filter(s -> s.key.equals(wanted)).findFirst();
    }

    static List<String> keys() {
        return Arrays.stream(values()).map(s -> s.key).toList();
    }

    private static List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        String wanted = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(wanted)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("expected one of " + names(type.getEnumConstants()));
    }

    static float parseFloat(String raw, float min, float max) {
        float value;
        try {
            // Accept a decimal comma as typed on id_ID keyboards; never use the JVM locale.
            value = Float.parseFloat(raw.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a number");
        }
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException("expected a number from " + min + " to " + max);
        }
        return value;
    }

    static int parseInt(String raw, int min, int max) {
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("expected a whole number");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException("expected a whole number from " + min + " to " + max);
        }
        return value;
    }

    static boolean parseBoolean(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException("expected true or false");
        };
    }

    static String parsePermission(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.equals("none") || value.equals("-")) {
            return "";
        }
        if (!PERMISSION_NODE.matcher(value).matches()) {
            throw new IllegalArgumentException("a permission may only contain a-z, 0-9, _, - and .");
        }
        return value;
    }

    static String parseBackground(String raw) {
        String value = raw.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("default")) {
            return "default";
        }
        if (lower.equals("none") || lower.equals("transparent")) {
            return "none";
        }
        if (!HEX.matcher(value).matches()) {
            throw new IllegalArgumentException("expected default, none, #RRGGBB or #AARRGGBB");
        }
        String hex = value.substring(1).toUpperCase(Locale.ROOT);
        return "#" + (hex.length() == 6 ? "FF" + hex : hex);
    }

    /** ARGB of a normalised background value; {@code null} for {@code default}. */
    static Integer backgroundArgb(String normalized) {
        return switch (normalized) {
            case "default" -> null;
            case "none" -> 0;
            default -> (int) Long.parseLong(normalized.substring(1), 16);
        };
    }
}
