package dev.yanianz.atrium.module.staff;

import java.util.List;

/**
 * Merges an inventory captured at the moment staff mode ends with the saved pre-staff inventory.
 *
 * <p>Pure and generic so the planning rules are unit-tested without a server. {@code null} means an
 * empty slot. Saved items always win; staff tools are never kept.
 */
final class InventoryMerge {
    private InventoryMerge() {
    }

    record Plan<T>(T[] target, List<T> leftovers) {
        Plan {
            leftovers = List.copyOf(leftovers);
        }
    }

    static <T> Plan<T> plan(T[] saved, T[] current, java.util.function.Predicate<T> isStaffItem,
            ExtraItemsMode mode) {
        T[] target = java.util.Arrays.copyOf(saved, saved.length);
        if (mode == ExtraItemsMode.CLEAR || current == null) {
            return new Plan<>(target, List.of());
        }
        List<T> leftovers = new java.util.ArrayList<>();
        for (T item : current) {
            if (item == null || isStaffItem.test(item)) {
                continue;
            }
            if (mode == ExtraItemsMode.DROP) {
                leftovers.add(item);
                continue;
            }
            int slot = firstEmpty(target);
            if (slot < 0) {
                leftovers.add(item);
            } else {
                target[slot] = item;
            }
        }
        return new Plan<>(target, leftovers);
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
