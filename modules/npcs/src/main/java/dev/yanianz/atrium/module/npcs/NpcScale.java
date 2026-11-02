package dev.yanianz.atrium.module.npcs;

import java.util.List;
import java.util.OptionalDouble;

/** Validation for {@code /npc scale}. Pure, so the bounds are unit-tested. */
final class NpcScale {
    static final double MIN = 0.1;
    static final double MAX = 5.0;
    private static final List<String> SUGGESTIONS = List.of("0.5", "0.75", "1", "1.5", "2", "3");

    private NpcScale() {}

    /** Parses a finite scale inside {@code [MIN, MAX]}; anything else is empty. */
    static OptionalDouble parse(String raw) {
        if (raw == null) {
            return OptionalDouble.empty();
        }
        String value = raw.trim();
        if (value.isEmpty() || value.contains(",")) {
            return OptionalDouble.empty();
        }
        try {
            double parsed = Double.parseDouble(value);
            return valid(parsed) ? OptionalDouble.of(parsed) : OptionalDouble.empty();
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    static boolean valid(double value) {
        return Double.isFinite(value) && value >= MIN && value <= MAX;
    }

    static List<String> suggestions() {
        return SUGGESTIONS;
    }
}
