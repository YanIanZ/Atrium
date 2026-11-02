package dev.yanianz.atrium.module.tablist;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Tab list engines. {@link #AUTO} is a configuration value only: it resolves to {@link #TAB} when the TAB plugin
 * is enabled and to {@link #NATIVE} otherwise, and is never the active engine.
 */
enum TablistEngine {
    AUTO("auto"),
    /** Atrium sends the header, footer, list names and list order itself. */
    NATIVE("native"),
    /** Atrium renders the texts and hands them to the TAB plugin through its API. */
    TAB("tab");

    final String id;

    TablistEngine(String id) {
        this.id = id;
    }

    /** Parses a config value; empty for an unknown one. */
    static Optional<TablistEngine> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String wanted = raw.strip().toLowerCase(Locale.ROOT);
        for (TablistEngine engine : values()) {
            if (engine.id.equals(wanted)) {
                return Optional.of(engine);
            }
        }
        return switch (wanted) {
            case "", "default" -> Optional.of(AUTO);
            case "internal", "atrium" -> Optional.of(NATIVE);
            case "neznamy", "tab-plugin" -> Optional.of(TAB);
            default -> Optional.empty();
        };
    }

    static List<String> names() {
        return Arrays.stream(values()).map(e -> e.id).toList();
    }
}
