package dev.yanianz.atrium.module.inventories;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amount-aware multiset difference between two slot lists.
 *
 * <p>{@link SlotView} supplies the identity of a slot without its amount, so moving a stack to another slot or
 * splitting one stack into several changes nothing. Only an amount that is really present in the edited copy
 * but absent from the base copy counts as added; that is exactly what an administrator contributed to an
 * offline inventory. Removals are reported separately and are never returned to the administrator.
 *
 * <p>Pure: no Bukkit types, so the arithmetic is unit tested without a running server.
 */
final class StackDelta {
    /** How to read one slot; implementations provide the item identity, its amount and emptiness. */
    interface SlotView<T> {
        boolean empty(T slot);

        /** Identity without the amount, for example the serialized item with a size of one. */
        String key(T slot);

        int amount(T slot);
    }

    /** Added and removed item counts, keyed by {@link SlotView#key}. Empty keys are never present. */
    record Diff(Map<String, Integer> added, Map<String, Integer> removed) {
        Diff {
            added = Map.copyOf(added);
            removed = Map.copyOf(removed);
        }

        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty();
        }

        /** How many items the edited copy gained compared with the base copy. */
        int addedItems() {
            return total(added);
        }

        /** How many base items are gone from the edited copy (destroyed by the administrator). */
        int removedItems() {
            return total(removed);
        }

        private static int total(Map<String, Integer> counts) {
            long sum = 0;
            for (int amount : counts.values()) {
                sum += Math.max(0, amount);
            }
            return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
        }
    }

    private StackDelta() {}

    static <T> Diff of(List<T> base, List<T> edited, SlotView<T> view) {
        Map<String, Integer> baseCounts = counts(base, view);
        Map<String, Integer> editedCounts = counts(edited, view);

        Map<String, Integer> added = new LinkedHashMap<>();
        editedCounts.forEach((key, amount) -> {
            int extra = amount - baseCounts.getOrDefault(key, 0);
            if (extra > 0) {
                added.put(key, extra);
            }
        });
        Map<String, Integer> removed = new LinkedHashMap<>();
        baseCounts.forEach((key, amount) -> {
            int missing = amount - editedCounts.getOrDefault(key, 0);
            if (missing > 0) {
                removed.put(key, missing);
            }
        });
        return new Diff(added, removed);
    }

    private static <T> Map<String, Integer> counts(List<T> slots, SlotView<T> view) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (T slot : slots) {
            if (slot == null || view.empty(slot)) {
                continue;
            }
            int amount = view.amount(slot);
            if (amount <= 0) {
                continue;
            }
            counts.merge(view.key(slot), amount, Integer::sum);
        }
        return counts;
    }
}
