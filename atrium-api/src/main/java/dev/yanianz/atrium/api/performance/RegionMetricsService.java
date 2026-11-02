package dev.yanianz.atrium.api.performance;

import java.util.Optional;

/**
 * Server-wide region metrics from a server that measures them itself (SourbyCraft's metrics runtime).
 * Published by the {@code sourbycraft} module when available. Thread-safe and non-blocking.
 */
public interface RegionMetricsService {
    /**
     * Aggregates of the last minute. Floating-point values are {@link Double#NaN} when unknown.
     *
     * @param source        name of the metrics source, for display
     * @param activeRegions regions ticking right now
     * @param worstTps      TPS of the slowest region
     * @param medianTps     median region TPS
     * @param averageMspt   average milliseconds per tick over all regions
     * @param p95Mspt       estimated 95th percentile tick time
     * @param maxMspt       longest tick
     * @param globalTps     TPS of the global scheduler (not a region)
     * @param freshness     state of the snapshot (for example {@code FRESH}, {@code STALE}), for display
     * @param ageMillis     age of the snapshot
     */
    record Summary(String source, int activeRegions, double worstTps, double medianTps, double averageMspt,
            double p95Mspt, double maxMspt, double globalTps, String freshness, long ageMillis) {}

    /** Latest summary, or empty when the server has none yet. */
    Optional<Summary> summary();
}
