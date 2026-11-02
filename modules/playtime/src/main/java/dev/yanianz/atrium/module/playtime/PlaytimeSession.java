package dev.yanianz.atrium.module.playtime;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * In-memory counters for one online session. {@code baseTotal} is the stored total at join; everything
 * counted afterwards is added on top, so a periodic flush can never be counted twice.
 *
 * <p>The join load and every administrative write share one per-player queue (see
 * {@code PlaytimeModule#enqueue}), so a mutation before {@link #loaded} can only be one whose storage write
 * the load's read already contains. Such a mutation is therefore ignored here instead of being applied on
 * top, which would count it twice.
 */
final class PlaytimeSession {
    private final UUID uuid;
    private final ZoneId zone;
    private long baseTotal = -1;
    private boolean ready;
    private long sessionSeconds;
    private long dayBase;
    private long todaySeconds;
    private LocalDate day;
    private long lastNanos = System.nanoTime();
    private boolean lastActive = true;
    private boolean finished;

    PlaytimeSession(UUID uuid, ZoneId zone) {
        this.uuid = uuid;
        this.zone = zone;
        this.day = LocalDate.now(zone);
    }

    UUID uuid() {
        return uuid;
    }

    synchronized void loaded(long total, long secondsToday) {
        this.baseTotal = Math.max(0, total);
        this.dayBase = Math.max(0, secondsToday);
        this.sessionSeconds = 0;
        this.todaySeconds = 0;
        this.day = LocalDate.now(zone);
        this.lastNanos = System.nanoTime();
        this.ready = true;
    }

    /** Snapshot used before an absolute write, so later ticks stay on top of the new total. */
    synchronized long[] progressSnapshot() {
        return new long[] {sessionSeconds, todaySeconds};
    }

    /**
     * Applies an absolute stored total. {@code sessionBefore}/{@code todayBefore} are the counters
     * captured before the storage write; ticks that happened afterwards remain counted on top.
     * Ignored while the session is still loading: the queued load reads storage after this write, so its
     * value already includes it.
     */
    synchronized void setStoredTotal(long total, long sessionBefore, long todayBefore) {
        if (!ready) {
            return;
        }
        baseTotal = total - sessionBefore;
        dayBase = total - todayBefore;
    }

    /**
     * Adds stored seconds to the live counter (used by the administrative add). Ignored while the session
     * is still loading, for the same reason as {@link #setStoredTotal}.
     */
    synchronized void addStored(long seconds) {
        if (seconds <= 0 || !ready) {
            return;
        }
        baseTotal += seconds;
        dayBase += seconds;
    }

    synchronized boolean ready() {
        return ready;
    }

    synchronized long total() {
        return ready ? baseTotal + sessionSeconds : -1;
    }

    synchronized long today() {
        return ready ? dayBase + todaySeconds : -1;
    }

    synchronized LocalDate day() {
        return day;
    }

    synchronized long sessionSeconds() {
        return sessionSeconds;
    }

    /**
     * Accounts the elapsed time. Returns the number of seconds to persist (0 when the player is AFK,
     * not loaded, or no measurable time passed).
     */
    synchronized long tick(boolean active, long maxSeconds) {
        if (finished) {
            return 0;
        }
        long now = System.nanoTime();
        long elapsed = Math.max(0, (now - lastNanos) / 1_000_000_000L);
        lastNanos = now;
        lastActive = active;
        if (!ready) {
            return 0;
        }
        LocalDate today = LocalDate.now(zone);
        if (!today.equals(day)) {
            day = today;
            dayBase = 0;
            todaySeconds = 0;
        }
        long seconds = Math.min(elapsed, Math.max(0, maxSeconds));
        if (seconds <= 0 || !active) {
            return 0;
        }
        sessionSeconds += seconds;
        todaySeconds += seconds;
        return seconds;
    }

    /** Final accounting at quit. Uses the activity of the last successful sample. */
    synchronized long finish(long maxSeconds) {
        long seconds = tick(lastActive, maxSeconds);
        finished = true;
        return seconds;
    }

    synchronized boolean finished() {
        return finished;
    }
}
