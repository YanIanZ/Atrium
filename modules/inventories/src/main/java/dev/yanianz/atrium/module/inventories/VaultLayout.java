package dev.yanianz.atrium.module.inventories;

import java.util.ArrayList;
import java.util.List;

/**
 * Sizes of the player vaults: how many vaults a player may have, how many rows one vault has, and what happens
 * when a stored vault is opened with a smaller size than it was saved with.
 *
 * <p>A smaller vault never deletes items: everything beyond the new size is returned as overflow, so the caller
 * can hand it back. This is the same rule the inventory snapshot restore follows.
 */
final class VaultLayout {
    /** Rows of one vault. Chests support at most six. */
    static final int MIN_ROWS = 1;
    static final int MAX_ROWS = 6;
    /** Columns of a chest row. */
    static final int COLUMNS = 9;
    /** Hard cap on the number of vaults, so a typo in a permission cannot create thousands of rows. */
    static final int MAX_VAULTS = 54;

    /** Items that fit the new size and the ones that must be handed back. */
    record Fit<T>(List<T> kept, List<T> overflow) {
        Fit {
            // Empty slots are null, so the lists must allow nulls; positions are part of the layout.
            kept = java.util.Collections.unmodifiableList(new ArrayList<>(kept));
            overflow = java.util.Collections.unmodifiableList(new ArrayList<>(overflow));
        }
    }

    private VaultLayout() {}

    static int rows(int configured) {
        return Math.clamp(configured, MIN_ROWS, MAX_ROWS);
    }

    static int slots(int configured) {
        return rows(configured) * COLUMNS;
    }

    static int vaultCount(int configured) {
        return Math.clamp(configured, 0, MAX_VAULTS);
    }

    /** The vault to open when the command named none: 1, or 0 when the player may not have any. */
    static int defaultVault(int count) {
        return vaultCount(count) > 0 ? 1 : 0;
    }

    static boolean validVault(int vault, int count) {
        return vault >= 1 && vault <= vaultCount(count);
    }

    /**
     * Trims or pads {@code items} to exactly {@code size} slots. Padding uses {@code null} (an empty slot) and
     * keeps positions, so a hopper layout survives. Anything beyond {@code size} is returned as overflow.
     */
    static <T> Fit<T> fit(List<T> items, int size) {
        int slots = Math.max(0, size);
        List<T> kept = new ArrayList<>(slots);
        List<T> overflow = new ArrayList<>();
        for (int i = 0; i < Math.max(slots, items.size()); i++) {
            T item = i < items.size() ? items.get(i) : null;
            if (i < slots) {
                kept.add(item);
            } else if (item != null) {
                overflow.add(item);
            }
        }
        return new Fit<>(kept, overflow);
    }
}
