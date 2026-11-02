package dev.yanianz.atrium.module.importer;

import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Progress of one phase of an import: how many items are done, the percentage and an ETA based on the
 * observed rate. Pure logic with an injectable clock so the arithmetic is testable without waiting.
 */
final class ImportProgress {
    private final LongSupplier clock;
    private volatile String phase = "starting";
    private volatile int processed;
    private volatile int total;
    private volatile long startedAt;
    private volatile long finishedAt;

    ImportProgress() {
        this(System::currentTimeMillis);
    }

    ImportProgress(LongSupplier clock) {
        this.clock = clock;
        this.startedAt = clock.getAsLong();
    }

    void begin(String phase, int total) {
        this.phase = phase == null ? "" : phase;
        this.total = Math.max(0, total);
        this.processed = 0;
        this.finishedAt = 0L;
    }

    void update(int processed) {
        this.processed = Math.max(0, processed);
    }

    /** Marks the run as done without discarding the counters of the last phase. */
    void complete() {
        this.phase = "done";
        this.finishedAt = clock.getAsLong();
    }

    Snapshot snapshot(boolean running) {
        long now = finishedAt > 0 ? finishedAt : clock.getAsLong();
        long elapsed = Math.max(0L, now - startedAt);
        return new Snapshot(phase, processed, total, percent(processed, total), etaSeconds(elapsed, processed, total),
                elapsed, startedAt, finishedAt, running);
    }

    static double percent(int processed, int total) {
        if (total <= 0) {
            return processed > 0 ? 100.0 : 0.0;
        }
        return Math.clamp(processed * 100.0 / total, 0.0, 100.0);
    }

    /** Seconds remaining at the current rate; -1 while the rate is unknown. */
    static long etaSeconds(long elapsedMillis, int processed, int total) {
        if (total <= 0 || processed <= 0) {
            return -1L;
        }
        if (processed >= total) {
            return 0L;
        }
        double perItem = (double) elapsedMillis / processed;
        long remaining = Math.round(perItem * (total - processed) / 1000.0);
        return Math.max(0L, remaining);
    }

    /** Compact duration for progress lines: {@code 9s}, {@code 2m 05s}, {@code 1h 03m}. */
    static String formatEta(long seconds) {
        if (seconds < 0) {
            return "?";
        }
        if (seconds < 60) {
            return seconds + "s";
        }
        if (seconds < 3600) {
            return String.format(Locale.ROOT, "%dm %02ds", seconds / 60, seconds % 60);
        }
        return String.format(Locale.ROOT, "%dh %02dm", seconds / 3600, (seconds % 3600) / 60);
    }

    record Snapshot(String phase, int processed, int total, double percent, long etaSeconds, long elapsedMillis,
            long startedAt, long finishedAt, boolean running) {
        String percentText() {
            return String.format(Locale.ROOT, "%.0f%%", percent);
        }

        String etaText() {
            return formatEta(etaSeconds);
        }
    }
}
