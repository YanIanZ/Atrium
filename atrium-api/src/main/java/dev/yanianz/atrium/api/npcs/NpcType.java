package dev.yanianz.atrium.api.npcs;

import java.util.Locale;

/**
 * The backend that displays an NPC.
 *
 * <p>{@link #AUTO} is only valid as a configuration value: the npcs module resolves it to the first
 * available engine (Citizens, then FancyNpcs, then the native Paper mannequin).
 */
public enum NpcType {
    /** Pick a backend automatically; never reported by a running NPC. */
    AUTO("auto"),
    /** Paper's {@code Mannequin} entity. Always available. */
    NATIVE("native"),
    /** An NPC owned by the Citizens plugin (soft dependency). */
    CITIZENS("citizens"),
    /** An NPC owned by the FancyNpcs plugin (soft dependency). */
    FANCYNPC("fancynpcs");

    private final String configName;

    NpcType(String configName) {
        this.configName = configName;
    }

    /** Lower-case name used in configs, commands and the database. */
    public String configName() {
        return configName;
    }

    /** Parses a config/command name ({@code fancy}, {@code fancynpcs}, ...); empty for an unknown value. */
    public static java.util.Optional<NpcType> parse(String input) {
        if (input == null || input.isBlank()) {
            return java.util.Optional.empty();
        }
        String value = input.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "auto" -> java.util.Optional.of(AUTO);
            case "native", "mannequin", "paper" -> java.util.Optional.of(NATIVE);
            case "citizens" -> java.util.Optional.of(CITIZENS);
            case "fancynpcs", "fancy", "fancy-npcs" -> java.util.Optional.of(FANCYNPC);
            default -> java.util.Optional.empty();
        };
    }
}
