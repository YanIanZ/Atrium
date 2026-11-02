package dev.yanianz.atrium.module.inventories;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Chooses which snapshots a player may keep. Pure: the store feeds it the stored ids and applies the answer.
 *
 * <p>Both limits are applied together: the newest {@code maxCount} snapshots are kept even when they are old,
 * and every snapshot older than the cutoff is removed even when it is among the newest. That way a changed
 * limit takes effect immediately and an idle player cannot keep stale data forever.
 */
final class Retention {
    record Entry(long id, long created) {}

    private Retention() {}

    /**
     * @param entries  every known snapshot of one player, in any order
     * @param maxCount how many snapshots to keep (0 removes everything)
     * @param cutoff   absolute age cutoff in epoch millis, or {@code 0} for no age limit
     * @return ids to delete; stable and free of duplicates
     */
    static List<Long> toDelete(List<Entry> entries, int maxCount, long cutoff) {
        List<Entry> sorted = new ArrayList<>(entries);
        // ids grow monotonically, so the highest id is the newest even when two snapshots share a timestamp.
        sorted.sort(Comparator.comparingLong(Entry::id).reversed());
        int keep = Math.max(0, maxCount);
        Set<Long> keepIds = new java.util.HashSet<>();
        for (Entry entry : sorted) {
            if (keepIds.size() >= keep) {
                break;
            }
            keepIds.add(entry.id());
        }
        Set<Long> delete = new LinkedHashSet<>();
        for (Entry entry : sorted) {
            boolean overCount = !keepIds.contains(entry.id());
            boolean tooOld = cutoff > 0 && entry.created() < cutoff;
            if (overCount || tooOld) {
                delete.add(entry.id());
            }
        }
        return List.copyOf(delete);
    }
}
