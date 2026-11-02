package dev.yanianz.atrium.module.display;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A named list of frames (trusted MiniMessage text). Each frame is shown for its own number of ticks;
 * the animation loops forever. Pure value: the frame at a tick is a function of that tick only, so every
 * viewer sees the same frame at the same time.
 */
public record Animation(String name, List<String> frames, List<Integer> durations) {
    public Animation {
        Objects.requireNonNull(name, "name");
        frames = List.copyOf(frames);
        durations = List.copyOf(durations);
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("animation " + name + " has no frames");
        }
        if (frames.size() != durations.size()) {
            throw new IllegalArgumentException("animation " + name + ": one duration per frame expected");
        }
        for (int d : durations) {
            if (d < 1) {
                throw new IllegalArgumentException("animation " + name + ": frame durations must be >= 1 tick");
            }
        }
    }

    /**
     * Builds an animation from config values. A {@code frameTicks} list with exactly one positive entry per
     * frame gives every frame its own duration; otherwise every frame lasts {@code intervalTicks} (min 1).
     */
    public static Animation of(String name, List<String> frames, int intervalTicks, List<Integer> frameTicks) {
        List<String> safeFrames = frames == null || frames.isEmpty() ? List.of("") : frames;
        int fallback = Math.max(1, intervalTicks);
        List<Integer> durations = new ArrayList<>(safeFrames.size());
        boolean perFrame = frameTicks != null && frameTicks.size() == safeFrames.size();
        for (int i = 0; i < safeFrames.size(); i++) {
            Integer own = perFrame ? frameTicks.get(i) : null;
            durations.add(own == null || own < 1 ? fallback : own);
        }
        return new Animation(name, safeFrames, durations);
    }

    /** Ticks of one full loop. */
    public long period() {
        long sum = 0;
        for (int d : durations) {
            sum += d;
        }
        return sum;
    }

    /** Index of the frame visible at {@code tick} (ticks since the clock started, may be any long). */
    public int indexAt(long tick) {
        long position = Math.floorMod(tick, period());
        for (int i = 0; i < durations.size(); i++) {
            position -= durations.get(i);
            if (position < 0) {
                return i;
            }
        }
        return durations.size() - 1;
    }

    public String frameAt(long tick) {
        return frames.get(indexAt(tick));
    }
}
