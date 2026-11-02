package dev.yanianz.atrium.module.worldtools;

/**
 * Adaptive chunks-per-tick for batch jobs. The floor is never below 1, so a job can always make progress
 * (a speed of 0 or below would stall forever).
 */
final class SpeedControl {
    private SpeedControl() {}

    static int floor(int configuredMin) {
        return Math.max(1, configuredMin);
    }

    /** Speeds up while nothing is pending, slows down while the backlog exceeds the current speed. */
    static int next(int current, int inFlight, int min, int max) {
        int lo = floor(min);
        int hi = Math.max(lo, max);
        int speed = current;
        if (inFlight <= 0) {
            speed = current + 1;
        } else if (inFlight > current) {
            speed = current - 2;
        }
        return Math.max(lo, Math.min(hi, speed));
    }

    /** How many new chunks to request now: never negative, never more than the speed. */
    static int dispatch(int speed, int inFlight) {
        return Math.max(0, Math.max(1, speed) - Math.max(0, inFlight));
    }
}
