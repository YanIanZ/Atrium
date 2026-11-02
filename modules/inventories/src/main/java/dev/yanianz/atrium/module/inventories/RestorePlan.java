package dev.yanianz.atrium.module.inventories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Pure planning of a replace restore into the live inventory sizes.
 *
 * <p>A snapshot may have been captured where the ender chest (or, on a fork, the storage) was larger than it is
 * now, for example a six-row snapshot loaded where the ender chest has three rows. Every section is cut to the
 * live size; every non-empty item past the live size is collected as overflow, in section order (storage,
 * armour, offhand, ender chest). The caller adds the overflow to the player inventory and drops what still
 * does not fit at the player's feet, so no item is ever silently lost. Empty slots past the live size are
 * ignored.
 */
final class RestorePlan {
    /** Live section sizes, read on the player's thread. */
    record Sizes(int storage, int armour, int offhand, int ender) {
        Sizes {
            storage = Math.max(0, storage);
            armour = Math.max(0, armour);
            offhand = Math.max(0, offhand);
            ender = Math.max(0, ender);
        }
    }

    /** Each section has exactly its live size; {@code overflow} holds only non-empty items. */
    record Plan<T>(List<T> storage, List<T> armour, List<T> offhand, List<T> ender, List<T> overflow) {
        Plan {
            storage = Collections.unmodifiableList(new ArrayList<>(storage));
            armour = Collections.unmodifiableList(new ArrayList<>(armour));
            offhand = Collections.unmodifiableList(new ArrayList<>(offhand));
            ender = Collections.unmodifiableList(new ArrayList<>(ender));
            overflow = Collections.unmodifiableList(new ArrayList<>(overflow));
        }

        boolean overflows() {
            return !overflow.isEmpty();
        }
    }

    private RestorePlan() {}

    static <T> Plan<T> plan(SnapshotCodec.Contents<T> contents, Sizes live, Predicate<T> empty) {
        List<T> overflow = new ArrayList<>();
        List<T> storage = fit(contents.storage(), live.storage(), overflow, empty);
        List<T> armour = fit(contents.armour(), live.armour(), overflow, empty);
        List<T> offhand = fit(contents.offhand(), live.offhand(), overflow, empty);
        List<T> ender = fit(contents.enderChest(), live.ender(), overflow, empty);
        return new Plan<>(storage, armour, offhand, ender, overflow);
    }

    /**
     * The first {@code size} slots of {@code section} (padded with {@code null}); every non-empty item past
     * {@code size} is appended to {@code overflow}.
     */
    static <T> List<T> fit(List<T> section, int size, List<T> overflow, Predicate<T> empty) {
        int slots = Math.max(0, size);
        List<T> placed = new ArrayList<>(slots);
        for (int slot = 0; slot < slots; slot++) {
            placed.add(slot < section.size() ? section.get(slot) : null);
        }
        for (int slot = slots; slot < section.size(); slot++) {
            T item = section.get(slot);
            if (item != null && !empty.test(item)) {
                overflow.add(item);
            }
        }
        return placed;
    }
}
