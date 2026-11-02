package dev.yanianz.atrium.module.scoreboard;

import java.util.Locale;
import java.util.function.BiConsumer;

/** Configured sidebar backend: {@code backend: auto|bukkit|packets}. */
enum BackendMode {
    /** Bukkit scoreboard API when the server has one (Paper), otherwise PacketEvents. */
    AUTO,
    /** Always the Bukkit scoreboard API; unavailable on Folia. */
    BUKKIT,
    /** Always PacketEvents packets; requires the packetevents plugin. */
    PACKETS;

    static BackendMode parse(String raw, BiConsumer<String, String> problems) {
        if (raw == null || raw.isBlank()) {
            return AUTO;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.accept("backend", "'" + raw + "' is not auto, bukkit or packets; using auto");
            return AUTO;
        }
    }
}
