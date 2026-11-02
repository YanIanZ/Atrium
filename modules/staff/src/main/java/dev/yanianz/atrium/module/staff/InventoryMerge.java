package dev.yanianz.atrium.module.staff;

import java.util.List;

/**
 * Merges an inventory captured at the moment staff mode ends with the saved pre-staff inventory.
 *
 * <p>Pure and generic so the planning rules are unit-tested without a server. {@code null} means an
 * empty slot. Saved items always win; staff tools are never kept.
 *
 * <p>The saved storage is cut to the live storage size. Saved items past the live size, plus saved items of
 * other sections that have no live slot (for example a fifth armour slot), are placed into empty storage slots
 * first; whatever still does not fit is returned as leftovers to be dropped at the player's feet, so a snapshot
 * taken with other inventory sizes never loses an item.
 */
final class InventoryMerge {
    private InventoryMerge() {
    }

    record Plan<T>(T[] target, List<T> leftovers) {
        Plan {
            leftovers = List.copyOf(leftovers);
        }
    }

    /** Same-size plan: the live storage is exactly as large as the saved one. */
    static <T> Plan<T> plan(T[] saved, T[] current, java.util.function.Predicate<T> isStaffItem,
            ExtraItemsMode mode) {
        return plan(saved, saved.length, List.of(), current, isStaffItem, mode);
    }

    /**
     * @param size        the live storage size; the returned target has exactly this many slots
     * @param savedExtras saved items that have no live slot of their own (armour or off hand overflow)
     */
    static <T> Plan<T> plan(T[] saved, int size, List<T> savedExtras, T[] current,
            java.util.function.Predicate<T> isStaffItem, ExtraItemsMode mode) {
        T[] target = java.util.Arrays.copyOf(saved, Math.max(0, size));
        List<T> leftovers = new java.util.ArrayList<>();
        List<T> spill = new java.util.ArrayList<>();
        for (int slot = target.length; slot < saved.length; slot++) {
            if (saved[slot] != null) {
                spill.add(saved[slot]);
            }
        }
        for (T item : savedExtras) {
            if (item != null) {
                spill.add(item);
            }
        }
        for (T item : spill) {
            place(target, item, leftovers);
        }
        if (mode == ExtraItemsMode.CLEAR || current == null) {
            return new Plan<>(target, leftovers);
        }
        for (T item : current) {
            if (item == null || isStaffItem.test(item)) {
                continue;
            }
            if (mode == ExtraItemsMode.DROP) {
                leftovers.add(item);
                continue;
            }
            place(target, item, leftovers);
        }
        return new Plan<>(target, leftovers);
    }

    private static <T> void place(T[] target, T item, List<T> leftovers) {
        int slot = firstEmpty(target);
        if (slot < 0) {
            leftovers.add(item);
        } else {
            target[slot] = item;
        }
    }

    private static <T> int firstEmpty(T[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                return i;
            }
        }
        return -1;
    }
}
