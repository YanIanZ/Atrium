package dev.yanianz.atrium.module.worldtools;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/worldtools.yml */
@ConfigSerializable
public class WorldToolsConfig {
    @Comment("Radius in blocks used by /replaceblock when none is given (1-maxReplaceRadius).")
    public int defaultReplaceRadius = 16;

    @Comment("Largest /replaceblock radius in blocks (1-256). The area is a cube around the player.")
    public int maxReplaceRadius = 64;

    @Comment("Minimum chunks requested per batch; never below 1 so a job always progresses.")
    public int minChunksPerBatch = 1;

    @Comment("Maximum chunks requested per batch (1-64). The speed adapts between min and max.")
    public int maxChunksPerBatch = 8;

    @Comment("Ticks between batches (1-200).")
    public int batchIntervalTicks = 2;

    @Comment("Seconds between progress messages (1-3600).")
    public int progressIntervalSeconds = 10;

    @Comment("How many /replaceblock jobs may run at the same time on the whole server (1-16).")
    public int maxConcurrentJobs = 2;

    @Comment("Largest /findbiome radius in blocks (64-12800).")
    public int maxBiomeRadius = 6400;

    @Comment("Radius used by /findbiome when none is given.")
    public int defaultBiomeRadius = 3200;

    @Comment("Selection wand: left-click sets corner 1, right-click corner 2 (/select pos1|pos2 also work).")
    public boolean selectionWandEnabled = true;

    @Comment("Item used as the selection wand (any item name, e.g. GOLDEN_HOE, STICK, BLAZE_ROD).")
    public String selectionWand = "GOLDEN_HOE";

    @Comment("How far (in blocks) /select pos1 and /select pos2 look for a block (1-64).")
    public int selectionReach = 8;

    @Comment("Largest selection in blocks for /replaceblock selection (1-16777216).\n"
            + "A bigger selection is refused instead of starting a job that would run for hours.")
    public long maxSelectionVolume = 1_000_000L;

    boolean selectionWandEnabled() {
        return selectionWandEnabled;
    }

    org.bukkit.Material selectionWandMaterial() {
        return SelectionListener.resolveWand(selectionWand);
    }

    int selectionReach() {
        return Math.max(1, Math.min(64, selectionReach));
    }

    long maxSelectionVolume() {
        return Math.max(1L, Math.min(Cuboid.MAX_VOLUME_HARD_LIMIT, maxSelectionVolume));
    }

    int maxReplaceRadius() {
        return clamp(maxReplaceRadius, 1, 256);
    }

    int defaultReplaceRadius() {
        return clamp(defaultReplaceRadius, 1, maxReplaceRadius());
    }

    int minChunksPerBatch() {
        return SpeedControl.floor(Math.min(minChunksPerBatch, 64));
    }

    int maxChunksPerBatch() {
        return clamp(maxChunksPerBatch, minChunksPerBatch(), 64);
    }

    int batchIntervalTicks() {
        return clamp(batchIntervalTicks, 1, 200);
    }

    int progressIntervalSeconds() {
        return clamp(progressIntervalSeconds, 1, 3600);
    }

    int maxConcurrentJobs() {
        return clamp(maxConcurrentJobs, 1, 16);
    }

    int maxBiomeRadius() {
        return clamp(maxBiomeRadius, 64, 12800);
    }

    int defaultBiomeRadius() {
        return clamp(defaultBiomeRadius, 64, maxBiomeRadius());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
