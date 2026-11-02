package dev.yanianz.atrium.module.itemtools;

import java.util.Locale;
import java.util.Optional;

/**
 * The cooldown stored on an item ({@code seconds:id}). Pure arithmetic: the server code stores the two
 * values as item data and keeps the per-player expiry in memory. An id is always lower case so the same
 * cooldown rolls over every copy of an item, independent of the material name's case.
 */
final class ItemCooldown {
    static final int MAX_SECONDS = 86_400;
    /** Used when an item has a cooldown but no explicit id. */
    static final String DEFAULT_ID = "item";

    record Metadata(int seconds, String id) {
        Metadata {
            if (seconds < 0 || seconds > MAX_SECONDS) {
                throw new IllegalArgumentException("Cooldown seconds must be between 0 and " + MAX_SECONDS);
            }
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("Cooldown id cannot be blank");
            }
        }
    }

    private ItemCooldown() {
    }

    static Metadata of(int seconds, String id) {
        return new Metadata(Math.clamp(seconds, 0, MAX_SECONDS), normalizeId(id));
    }

    /** Lower-case, limited to letters, digits, {@code _}, {@code -}, {@code .} and {@code :}. */
    static String normalizeId(String id) {
        if (id == null) {
            return DEFAULT_ID;
        }
        String normalized = id.strip().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]", "")
                .replaceAll("^[._:-]+", "");
        if (normalized.isEmpty()) {
            return DEFAULT_ID;
        }
        return normalized.length() <= 64 ? normalized : normalized.substring(0, 64);
    }

    /** Ceiling of the remaining seconds, so a 1 ms remainder still reads as "1 second". */
    static int remainingSeconds(long nowMillis, long readyAtMillis) {
        long remaining = readyAtMillis - nowMillis;
        if (remaining <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (remaining + 999L) / 1000L);
    }

    static boolean ready(long nowMillis, long readyAtMillis) {
        return nowMillis >= readyAtMillis;
    }

    static long readyAt(long nowMillis, int seconds) {
        long millis = Math.clamp(seconds, 0, MAX_SECONDS) * 1000L;
        long ready = nowMillis + millis;
        return ready < nowMillis ? Long.MAX_VALUE : ready;
    }

    /** Parses an admin id; empty means "use the default id". */
    static String parseId(Optional<String> raw) {
        return raw.map(ItemCooldown::normalizeId).orElse(DEFAULT_ID);
    }
}
