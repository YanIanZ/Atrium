package dev.yanianz.atrium.module.staff;

import java.util.ArrayList;
import java.util.List;

/**
 * Sliding-window speed calculation for {@code /speed}.
 *
 * <p>Samples are taken on the player's own thread. A sample older than {@link #WINDOW_MILLIS} is dropped, and
 * a jump larger than {@link #MAX_JUMP} blocks between two samples is treated as a teleport: the window is
 * cleared instead of reporting the absurd speed of the jump. That is what keeps a warp, an elytra burst or a
 * lag spike from showing up as thousands of km/h.
 *
 * <p>{@link #kmh} uses the oldest and newest surviving sample, so the value is an average over the window
 * rather than one noisy tick.
 */
final class SpeedSamples {
    static final long WINDOW_MILLIS = 500;
    static final double MAX_JUMP = 20.0;

    record Sample(long time, double x, double y, double z) {
        double distance(Sample other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    private SpeedSamples() {}

    /**
     * Adds {@code sample} to the window. The returned list is a new immutable list; the input is not changed.
     * A jump larger than {@link #MAX_JUMP} starts a fresh window at the new position.
     */
    static List<Sample> add(List<Sample> window, Sample sample) {
        List<Sample> kept = new ArrayList<>(window.size() + 1);
        if (!window.isEmpty()) {
            Sample last = window.get(window.size() - 1);
            if (sample.time() < last.time() || last.distance(sample) > MAX_JUMP) {
                // Out of order or a teleport: nothing before it can be compared.
                kept.add(sample);
                return List.copyOf(kept);
            }
        }
        for (Sample old : window) {
            if (sample.time() - old.time() <= WINDOW_MILLIS) {
                kept.add(old);
            }
        }
        kept.add(sample);
        return List.copyOf(kept);
    }

    /** Average speed in km/h over the window, or 0 when there are fewer than two usable samples. */
    static double kmh(List<Sample> window) {
        if (window.size() < 2) {
            return 0;
        }
        Sample first = window.get(0);
        Sample last = window.get(window.size() - 1);
        long millis = last.time() - first.time();
        if (millis <= 0) {
            return 0;
        }
        double blocksPerSecond = first.distance(last) / (millis / 1000.0);
        return blocksPerSecond * 3.6;
    }

    /** Formats a speed for the action bar ("12.3"). */
    static String format(double kmh) {
        return String.format(java.util.Locale.ROOT, "%.1f", Math.max(0, kmh));
    }
}
