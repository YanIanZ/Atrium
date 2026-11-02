package dev.yanianz.atrium.module.teleport;

import java.util.Optional;

/**
 * One coordinate as typed in a command: absolute ({@code 100}, {@code -3.5}) or relative to the
 * player ({@code ~}, {@code ~5}, {@code ~-2.5}). Whole absolute X/Z numbers are centred on the block
 * ({@code 100} → {@code 100.5}), like vanilla.
 */
record RelativeCoordinate(boolean relative, double value) {
    /** Largest absolute horizontal coordinate accepted (the world border limit). */
    static final double HORIZONTAL_LIMIT = 30_000_000;

    static Optional<RelativeCoordinate> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String s = text.trim();
        if (s.isEmpty()) {
            return Optional.empty();
        }
        boolean relative = s.charAt(0) == '~';
        String number = relative ? s.substring(1) : s;
        if (relative && number.isEmpty()) {
            return Optional.of(new RelativeCoordinate(true, 0));
        }
        // Only plain decimal numbers: no exponent, hex, NaN or Infinity.
        if (!number.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")) {
            return Optional.empty();
        }
        double value = Double.parseDouble(number);
        if (!Double.isFinite(value) || Math.abs(value) > HORIZONTAL_LIMIT * 2) {
            return Optional.empty();
        }
        return Optional.of(new RelativeCoordinate(relative, value));
    }

    /**
     * The resulting coordinate.
     *
     * @param base           the player's current coordinate on this axis
     * @param centerIntegers add 0.5 to whole absolute numbers (X and Z axes)
     */
    double resolve(double base, boolean centerIntegers) {
        if (relative) {
            return base + value;
        }
        if (centerIntegers && value == Math.rint(value)) {
            return value + 0.5;
        }
        return value;
    }

    /** Absolute X/Z values beyond the world limit are rejected. */
    static boolean withinHorizontalLimit(double coordinate) {
        return Math.abs(coordinate) <= HORIZONTAL_LIMIT;
    }
}
