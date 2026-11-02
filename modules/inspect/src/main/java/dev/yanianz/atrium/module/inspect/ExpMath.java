package dev.yanianz.atrium.module.inspect;

/**
 * Vanilla experience maths. Total experience is computed from the level and the 0..1 progress bar
 * instead of {@code Player#getTotalExperience()}, which is only kept up to date by vanilla gameplay
 * and can be stale after plugins change the level directly.
 *
 * <p>Cost of the next level: {@code 2L+7} for 0-15, {@code 5L-38} for 16-30 and {@code 9L-158} for
 * 31+. The cumulative forms below are the closed sums of those formulas.
 */
final class ExpMath {
    /** Levels above this are clamped; the real limit is far below it and this keeps the maths in range. */
    static final int MAX_LEVEL = 100_000;

    private ExpMath() {}

    /** Experience required to go from level 0 to {@code level}. */
    static long expToReachLevel(int level) {
        if (level <= 0) {
            return 0;
        }
        long l = Math.min(level, MAX_LEVEL);
        if (l <= 16) {
            return l * l + 6 * l;
        }
        if (l <= 31) {
            return (5 * l * l - 81 * l + 720) / 2;
        }
        return (9 * l * l - 325 * l + 4440) / 2;
    }

    /** Experience required to go from {@code level} to {@code level + 1}. */
    static long expToNextLevel(int level) {
        if (level < 0) {
            return 0;
        }
        long l = Math.min(level, MAX_LEVEL);
        if (l <= 15) {
            return 2 * l + 7;
        }
        if (l <= 30) {
            return 5 * l - 38;
        }
        return 9 * l - 158;
    }

    /** Total experience for a level and a progress bar value in [0, 1]. */
    static long totalExp(int level, double progress) {
        long base = expToReachLevel(level);
        long next = expToNextLevel(level);
        double clamped = Math.max(0, Math.min(1, progress));
        long inside = (long) Math.floor(next * clamped);
        return base + Math.min(next, Math.max(0, inside));
    }

    /** Highest level whose start cost is at most {@code total}. */
    static int levelForTotal(long total) {
        if (total <= 0) {
            return 0;
        }
        int low = 0;
        int high = MAX_LEVEL;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (expToReachLevel(middle) <= total) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return low;
    }

    /** Progress bar value in [0, 1] for a total experience amount. */
    static double progressForTotal(long total) {
        int level = levelForTotal(total);
        long next = expToNextLevel(level);
        if (next <= 0) {
            return 0;
        }
        long inside = Math.max(0, total - expToReachLevel(level));
        return Math.min(1, (double) inside / next);
    }
}
