package dev.yanianz.atrium.module.playtime;

import java.util.Locale;
import java.util.Optional;

/**
 * Arithmetic behind {@code /editplaytime}. Pure so the limits (a take never goes below zero, an add
 * never overflows) are covered by unit tests instead of being trusted from command parsing.
 */
final class PlaytimeEdit {
    private PlaytimeEdit() {}

    enum Mode {
        SET,
        ADD,
        TAKE;

        static Optional<Mode> parse(String input) {
            if (input == null) {
                return Optional.empty();
            }
            return switch (input.strip().toLowerCase(Locale.ROOT)) {
                case "set" -> Optional.of(SET);
                case "add" -> Optional.of(ADD);
                case "take", "remove", "sub" -> Optional.of(TAKE);
                default -> Optional.empty();
            };
        }

        String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The new absolute total after applying an edit. A negative amount is rejected. */
    static long nextTotal(long current, Mode mode, long seconds) {
        if (mode == null) {
            throw new IllegalArgumentException("playtime edit mode is required");
        }
        if (seconds < 0) {
            throw new IllegalArgumentException("playtime seconds must not be negative: " + seconds);
        }
        long safeCurrent = Math.max(0, current);
        return switch (mode) {
            case SET -> seconds;
            // Saturate instead of wrapping into a negative total (int overflow must never happen).
            case ADD -> safeCurrent > Long.MAX_VALUE - seconds ? Long.MAX_VALUE : safeCurrent + seconds;
            case TAKE -> Math.max(0, safeCurrent - seconds);
        };
    }
}
