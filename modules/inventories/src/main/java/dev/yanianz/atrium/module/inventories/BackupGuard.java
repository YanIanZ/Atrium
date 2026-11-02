package dev.yanianz.atrium.module.inventories;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Guards a destructive change (load / clear) behind a backup that really matches what is destroyed. The backup is
 * captured, stored asynchronously, and only then applied on the player's thread; in between the player may pick up
 * or move items. Right before applying, the contents are captured again and compared with the backup: when they
 * differ, a fresh backup is taken (bounded), so nothing is overwritten that is not in a stored snapshot.
 */
final class BackupGuard {
    /** Total backup attempts before giving up and asking the admin to retry. */
    static final int MAX_ATTEMPTS = 3;

    enum Decision { APPLY, REBACKUP, ABORT }

    private BackupGuard() {}

    /** What to do after re-checking; {@code attempt} counts from 1. */
    static Decision decide(boolean unchanged, int attempt) {
        if (unchanged) return Decision.APPLY;
        return attempt < MAX_ATTEMPTS ? Decision.REBACKUP : Decision.ABORT;
    }

    /**
     * True when the stored backup still describes the live contents: identical serialized bytes, or (should the
     * encoding not be byte-stable) equal items slot by slot.
     */
    static <T> boolean unchanged(byte[] backupBytes, byte[] nowBytes, List<List<T>> backupSlots,
            List<List<T>> nowSlots, Predicate<T> empty) {
        if (Arrays.equals(backupBytes, nowBytes)) return true;
        if (backupSlots.size() != nowSlots.size()) return false;
        for (int i = 0; i < backupSlots.size(); i++) {
            if (!sameSlots(backupSlots.get(i), nowSlots.get(i), empty)) return false;
        }
        return true;
    }

    /** Slot-by-slot equality where {@code null} and empty items are the same and missing tail slots are empty. */
    static <T> boolean sameSlots(List<T> first, List<T> second, Predicate<T> empty) {
        int size = Math.max(first.size(), second.size());
        for (int slot = 0; slot < size; slot++) {
            T a = slot < first.size() ? first.get(slot) : null;
            T b = slot < second.size() ? second.get(slot) : null;
            boolean aEmpty = a == null || empty.test(a);
            boolean bEmpty = b == null || empty.test(b);
            if (aEmpty || bEmpty) {
                if (aEmpty != bEmpty) return false;
                continue;
            }
            if (!Objects.equals(a, b)) return false;
        }
        return true;
    }
}
