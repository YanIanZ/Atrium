package dev.yanianz.atrium.module.inventories;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Locale-safe, deterministic timestamp text used in chat and previews. */
final class SnapshotText {
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private SnapshotText() {}

    static String time(long epochMillis) {
        return FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }
}
