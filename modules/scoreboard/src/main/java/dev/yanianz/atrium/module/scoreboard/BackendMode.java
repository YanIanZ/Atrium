package dev.yanianz.atrium.module.scoreboard;

import java.util.Locale;
import java.util.function.BiConsumer;

/** Configured sidebar backend: {@code backend: auto|bukkit|packets|tab}. */
enum BackendMode {
    /** TAB when its scoreboard feature is on, else the Bukkit scoreboard API (Paper), else PacketEvents (Folia). */
    AUTO,
    /** Always the Bukkit scoreboard API; unavailable on Folia. */
    BUKKIT,
    /** Always PacketEvents packets; requires the packetevents plugin. */
    PACKETS,
    /**
     * The TAB plugin's scoreboard API; falls back to the auto choice without TAB when TAB or its scoreboard
     * feature is missing.
     */
    TAB;

    static BackendMode parse(String raw, BiConsumer<String, String> problems) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.accept("backend", "'" + raw + "' is not auto, bukkit, packets or tab; using auto");
            return AUTO;
        }
    }
}
