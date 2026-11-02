package dev.yanianz.atrium.module.search;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/search.yml */
@ConfigSerializable
public class SearchConfig {
    @Comment("Maximum online players one /search item run inspects (1-10000).")
    public int maxPlayersPerSearch = 200;

    @Comment("Seconds to wait for the per-player inventory reads before reporting partial results (5-300).")
    public int searchTimeoutSeconds = 20;

    @Comment("Player rows shown per page by /search results (1-100).")
    public int resultsPageSize = 10;

    @Comment("Square radius in chunks used by /scan containers when none is given (1-maxScanRadius).")
    public int defaultScanRadius = 2;

    @Comment("Largest /scan containers radius in chunks (1-32). Bounds the work a single scan can start.")
    public int maxScanRadius = 8;

    @Comment("Minimum chunk checks dispatched per tick; never below 1 so a scan always progresses (1-64).")
    public int minChunksPerTick = 1;

    @Comment("Maximum chunk checks dispatched per tick (1-64).")
    public int maxChunksPerTick = 8;

    @Comment("Seconds between /scan containers progress messages (1-3600).")
    public int progressIntervalSeconds = 5;

    @Comment("Hard cap on stored /scan containers hits (1-10000).")
    public int maxContainerResults = 200;

    @Comment("Hit lines printed when a container scan finishes (1-100).")
    public int maxContainerLines = 20;

    @Comment("Seconds to wait for /scan entities around all players before reporting partial results (5-300).")
    public int entityScanTimeoutSeconds = 20;

    @Comment("Hard cap on loaded chunks one /scan entities run inspects (1-200000).")
    public int maxEntityScanChunks = 5000;

    @Comment("How many chunks /scan entities lists per category (1-100).")
    public int topChunkCount = 10;

    @Comment("Maximum online player regions one /scan entities run uses as centres (1-200).")
    public int maxEntityScanPlayers = 50;

    int maxPlayersPerSearch() {
        return clamp(maxPlayersPerSearch, 1, 10_000);
    }

    int searchTimeoutSeconds() {
        return clamp(searchTimeoutSeconds, 5, 300);
    }

    int resultsPageSize() {
        return clamp(resultsPageSize, 1, 100);
    }

    int maxScanRadius() {
        return clamp(maxScanRadius, 1, 32);
    }

    int defaultScanRadius() {
        return clamp(defaultScanRadius, 1, maxScanRadius());
    }

    int minChunksPerTick() {
        return ScanSpeed.floor(Math.min(minChunksPerTick, 64));
    }

    int maxChunksPerTick() {
        return clamp(maxChunksPerTick, minChunksPerTick(), 64);
    }

    int progressIntervalSeconds() {
        return clamp(progressIntervalSeconds, 1, 3600);
    }

    int maxContainerResults() {
        return clamp(maxContainerResults, 1, 10_000);
    }

    int maxContainerLines() {
        return clamp(maxContainerLines, 1, 100);
    }

    int entityScanTimeoutSeconds() {
        return clamp(entityScanTimeoutSeconds, 5, 300);
    }

    int maxEntityScanChunks() {
        return clamp(maxEntityScanChunks, 1, 200_000);
    }

    int topChunkCount() {
        return clamp(topChunkCount, 1, 100);
    }

    int maxEntityScanPlayers() {
        return clamp(maxEntityScanPlayers, 1, 200);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
