package dev.yanianz.atrium.module.inspect;

import dev.yanianz.atrium.api.statistics.StatisticsCatalog;
import java.util.Locale;
import java.util.Optional;

/**
 * Arithmetic of {@code /statsedit}. The operator types the amount in the unit the statistic is displayed
 * in (seconds, metres or a plain count); the stored Bukkit value uses ticks, centimetres or the count.
 * Pure, so the clamping rules are unit tested without a server.
 */
final class StatsEdit {
    enum Mode {
        SET,
        ADD,
        TAKE;

        static Optional<Mode> parse(String raw) {
            if (raw == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(raw.strip().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    private StatsEdit() {}

    /** Converts an amount typed by an operator into the raw statistic unit, saturating instead of overflowing. */
    static long toRaw(long amount, StatisticsCatalog.Kind kind) {
        long positive = Math.max(0L, amount);
        long factor = switch (kind) {
            case TIME -> StatisticsCatalog.ticksPerSecond();
            case DISTANCE -> StatisticsCatalog.centimetresPerMetre();
            case COUNT -> 1L;
        };
        if (positive > Long.MAX_VALUE / factor) {
            return Long.MAX_VALUE;
        }
        return positive * factor;
    }

    /** Applies a raw delta to the current value, clamped to the range Bukkit can store. */
    static int apply(int current, Mode mode, long rawDelta) {
        long base = Math.max(0L, current);
        long delta = Math.max(0L, rawDelta);
        long result = switch (mode) {
            case SET -> delta;
            case ADD -> base > Long.MAX_VALUE - delta ? Long.MAX_VALUE : base + delta;
            case TAKE -> base - Math.min(base, delta);
        };
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, result));
    }

    /** The same computation with an amount in display units. */
    static int apply(int current, Mode mode, long amount, StatisticsCatalog.Kind kind) {
        return apply(current, mode, toRaw(amount, kind));
    }
}
