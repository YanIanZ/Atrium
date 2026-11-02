package dev.yanianz.atrium.module.worldtools;

/**
 * Per-player token bucket for mirror operations. One token is one mirrored block, so a large mirror
 * cannot be used to write thousands of blocks in a tick. Pure (the clock is passed in), so the limit is
 * unit tested.
 */
final class MirrorRateLimiter {
    private long credit;
    private long lastNanos;
    private boolean started;

    /**
     * @param operations how many blocks this operation mirrors (at least one)
     * @return {@code true} when every requested operation may run now; nothing is consumed otherwise
     */
    boolean tryAcquire(long nowNanos, int operations, int perSecond, int burst) {
        int rate = Math.max(1, perSecond);
        int capacityOperations = Math.max(1, burst);
        long cost = Math.max(1L, 1_000_000_000L / rate);
        long capacity = cost * capacityOperations;
        if (!started) {
            credit = capacity;
            lastNanos = nowNanos;
            started = true;
        }
        if (nowNanos > lastNanos) {
            credit = Math.min(capacity, credit + (nowNanos - lastNanos));
            lastNanos = nowNanos;
        }
        long need = cost * Math.max(1, operations);
        if (credit < need) {
            return false;
        }
        credit -= need;
        return true;
    }
}
