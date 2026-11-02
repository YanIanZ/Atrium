package dev.yanianz.atrium.module.afk;

/**
 * Immutable per-player AFK totals. The AFK state itself is transient; only these statistics are stored so
 * other modules (and a future /afkstats command) can use them.
 */
public record AfkProfile(long totalSeconds, long sessions) {
    public static final AfkProfile EMPTY = new AfkProfile(0, 0);

    public AfkProfile {
        if (totalSeconds < 0 || sessions < 0) {
            throw new IllegalArgumentException("AFK totals cannot be negative");
        }
    }

    /** Adds one finished AFK session, saturating instead of wrapping. */
    public AfkProfile plus(long seconds) {
        if (seconds <= 0) {
            return this;
        }
        long total = totalSeconds > Long.MAX_VALUE - seconds ? Long.MAX_VALUE : totalSeconds + seconds;
        long count = sessions == Long.MAX_VALUE ? Long.MAX_VALUE : sessions + 1;
        return new AfkProfile(total, count);
    }
}
