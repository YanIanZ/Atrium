package dev.yanianz.atrium.module.inventories;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/inventories.yml */
@ConfigSerializable
public class InventoriesConfig {
    @Comment("Save a snapshot of the inventory, armour, offhand and ender chest when a player dies.")
    public boolean captureOnDeath = true;

    @Comment("Save a snapshot when a player quits. This also lets /invsave copy the last known state while the player is offline.")
    public boolean captureOnQuit = true;

    @Comment("Snapshots kept per player (1-1000). The oldest are removed after every new snapshot.")
    public int maxSnapshotsPerPlayer = 10;

    @Comment("Delete snapshots older than this many days (0 = keep forever). Checked on enable and every 6 hours.")
    public int maxAgeDays = 90;

    @Comment("Delete resolved offline edit rows older than this many days (0 = keep forever). Open edits wait until the player joins.")
    public int offlineEditMaxAgeDays = 30;

    @Comment("Seconds in which the same command must be repeated to confirm a load, a bulk removal or an ender chest clear (1-300).")
    public int confirmationSeconds = 15;

    @Comment("Snapshot entries per page in /invlist (1-20).")
    public int listPageSize = 8;

    @Comment("Maximum length of a snapshot label (1-64 characters).")
    public int maxLabelLength = 48;

    static int pageSize(InventoriesConfig config) {
        return Math.clamp(config.listPageSize, 1, 20);
    }

    static int labelLimit(InventoriesConfig config) {
        return Math.clamp(config.maxLabelLength, 1, 64);
    }

    static int retentionCount(InventoriesConfig config) {
        return Math.clamp(config.maxSnapshotsPerPlayer, 1, 1000);
    }

    static int confirmationSeconds(InventoriesConfig config) {
        return Math.clamp(config.confirmationSeconds, 1, 300);
    }

    /** Cutoff in epoch millis, or {@code 0} when snapshots never expire. */
    static long cutoff(InventoriesConfig config, long now) {
        int days = Math.clamp(config.maxAgeDays, 0, 36_500);
        return days == 0 ? 0L : now - days * 86_400_000L;
    }

    /** Cutoff in epoch millis for pending offline edits, or {@code 0} when they never expire. */
    static long pendingCutoff(InventoriesConfig config, long now) {
        int days = Math.clamp(config.offlineEditMaxAgeDays, 0, 36_500);
        return days == 0 ? 0L : now - days * 86_400_000L;
    }
}
