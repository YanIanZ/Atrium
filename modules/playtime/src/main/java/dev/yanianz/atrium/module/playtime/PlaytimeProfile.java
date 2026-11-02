package dev.yanianz.atrium.module.playtime;

import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-player playtime data. Only completed claim watermarks are kept here; the
 * {@code playtime_claims} table is authoritative and is written before and after a delivery.
 */
record PlaytimeProfile(Map<String, Long> watermarks) {
    static final PlaytimeProfile EMPTY = new PlaytimeProfile(Map.of());

    PlaytimeProfile {
        watermarks = Map.copyOf(watermarks);
    }

    Long watermark(String rewardId) {
        return watermarks.get(rewardId);
    }

    /** Advances a watermark, never moving it backwards. */
    PlaytimeProfile withWatermark(String rewardId, long watermark) {
        Objects.requireNonNull(rewardId, "rewardId");
        long current = watermarks.getOrDefault(rewardId, Long.MIN_VALUE);
        if (current >= watermark) {
            return this;
        }
        Map<String, Long> next = new java.util.HashMap<>(watermarks);
        next.put(rewardId, watermark);
        return new PlaytimeProfile(next);
    }
}
