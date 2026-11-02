package dev.yanianz.atrium.module.worldrules;

import java.util.Locale;

/** The three weather settings {@code /weather} accepts. */
enum WeatherKind {
    CLEAR(false, false),
    RAIN(true, false),
    STORM(true, true);

    private final boolean storm;
    private final boolean thundering;

    WeatherKind(boolean storm, boolean thundering) {
        this.storm = storm;
        this.thundering = thundering;
    }

    boolean storm() {
        return storm;
    }

    boolean thundering() {
        return thundering;
    }

    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** {@code null} for an unknown name; accepts sun/clear, rain and storm/thunder. */
    static WeatherKind parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "clear", "sun", "sunny" -> CLEAR;
            case "rain", "rainy", "downfall" -> RAIN;
            case "storm", "thunder", "thunderstorm" -> STORM;
            default -> null;
        };
    }
}
