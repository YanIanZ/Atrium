package dev.yanianz.atrium.module.search;

/**
 * Adaptive chunk budget for the container scan. The floor is always at least one, so a scan always
 * makes progress; a speed of zero would stall. Pure logic, unit tested.
 */
public final class ScanSpeed {
    private ScanSpeed() {}

    public static int floor(int configuredMin) {
        return Math.max(1, configuredMin);
    }

    /** Speeds up while nothing is pending, slows down while the backlog exceeds the budget. */
    public static int next(int current, int inFlight, int min, int max) {
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

    /** How many new chunks to request now: never negative, never more than the budget. */
    public static int dispatch(int speed, int inFlight) {
        return Math.max(0, Math.max(1, speed) - Math.max(0, inFlight));
    }
}
