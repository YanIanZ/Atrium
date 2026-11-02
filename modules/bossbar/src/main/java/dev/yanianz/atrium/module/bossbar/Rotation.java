package dev.yanianz.atrium.module.bossbar;

import java.util.List;

/**
 * Which announcement is visible at a tick. Every {@code intervalTicks} the next announcement starts and stays
 * for its own duration (capped at the interval). Pure function of the tick, computed on the global timer.
 */
record Rotation(long intervalTicks, List<Long> durationTicks, boolean random) {
    /**
     * @param index    announcement index
     * @param slot     rotation slot number; a new slot means a new bar even for the same index
     * @param elapsed  fraction of the announcement's duration already shown, 0 (start) to 1 (end)
     */
    record Active(int index, long slot, double elapsed) {}

    Rotation {
        if (intervalTicks < 1) {
            throw new IllegalArgumentException("interval must be >= 1 tick");
        }
        durationTicks = List.copyOf(durationTicks);
    }

    /** Visible announcement at {@code tick}, or {@code null} in a gap or when there are none. */
    Active at(long tick) {
        int count = durationTicks.size();
        if (count == 0 || tick < 0) {
            return null;
        }
        long slot = tick / intervalTicks;
        long within = tick % intervalTicks;
        int index = indexOf(slot, count);
        long duration = Math.max(1, Math.min(durationTicks.get(index), intervalTicks));
        if (within >= duration) {
            return null;
        }
        return new Active(index, slot, (double) within / duration);
    }

    private int indexOf(long slot, int count) {
        if (!random || count == 1) {
            return (int) Math.floorMod(slot, (long) count);
        }
        // Shuffle each full cycle with a fixed seed so that every announcement still appears once per cycle.
        long cycle = Math.floorDiv(slot, (long) count);
        int position = (int) Math.floorMod(slot, (long) count);
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(cycle * 0x9E3779B97F4A7C15L);
        for (int i = count - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        return order[position];
    }

    /** Bar fill: countdown empties from 1 to 0, static keeps the configured value. */
    static float progress(boolean countdown, double staticProgress, double elapsed) {
        double value = countdown ? 1.0 - elapsed : staticProgress;
        if (Double.isNaN(value)) {
            return 1f;
        }
        return (float) Math.clamp(value, 0.0, 1.0);
    }
}
