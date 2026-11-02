package dev.yanianz.atrium.api.inventories;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Administrative import of whole personal vaults ({@code /pv}). Published by the {@code inventories}
 * module; the importer uses it to carry vaults over from another plugin.
 *
 * <p>An import is per player all-or-nothing: every vault of one owner is written in a single database
 * transaction, so a failure never leaves some of the player's vaults imported and others not. Without
 * {@code overwrite} nothing is written when any of the player's vaults already exists.
 *
 * <p>Slots are positional: index 0 is the first slot of the vault. An empty slot is {@code null} or an
 * empty stack. All methods are safe to call from any thread and never block a game thread on storage. The
 * item stacks are encoded inside the inventories module; the caller only builds them.
 */
public interface VaultImportService {
    /** Columns of a vault row. */
    int COLUMNS = 9;
    /** Largest vault: six rows, as in {@code /pv}. */
    int MAX_ROWS = 6;
    /** Largest vault in slots. */
    int MAX_SLOTS = MAX_ROWS * COLUMNS;

    /**
     * One vault to import.
     *
     * @param number 1-based vault number, 1 to {@value #MAX_SLOTS}
     * @param rows   rows of the vault, 1 to {@value #MAX_ROWS}
     * @param items  slot contents; shorter lists are padded with empty slots, a longer list is refused
     *               because its items would be lost
     */
    record Vault(int number, int rows, @Nullable List<ItemStack> items) {
        public Vault {
            if (number < 1 || number > MAX_SLOTS) {
                throw new IllegalArgumentException("vault number out of range: " + number);
            }
            if (rows < 1 || rows > MAX_ROWS) {
                throw new IllegalArgumentException("vault rows out of range: " + rows);
            }
            List<ItemStack> source = items == null ? List.of() : items;
            int slots = rows * COLUMNS;
            if (source.size() > slots) {
                throw new IllegalArgumentException(
                        "vault " + number + " has " + source.size() + " slots but only " + slots + " fit");
            }
            List<ItemStack> padded = new ArrayList<>(slots);
            for (int i = 0; i < slots; i++) {
                ItemStack item = i < source.size() ? source.get(i) : null;
                padded.add(item == null || item.isEmpty() ? null : item.clone());
            }
            // Empty slots are null, so the list must allow nulls.
            items = java.util.Collections.unmodifiableList(padded);
        }

        public int slots() {
            return rows * COLUMNS;
        }
    }

    /** Outcome of one player's import; {@code SKIPPED} means nothing was written. */
    enum Result {
        /** All vaults of the player were stored. */
        IMPORTED,
        /** At least one vault already existed and {@code overwrite} was not set. */
        SKIPPED,
        /** The import was refused because it would not fit or was invalid. */
        INVALID
    }

    /** Numbers of the vaults this owner already has in Atrium. */
    CompletableFuture<Set<Integer>> existingVaults(UUID owner);

    /**
     * Stores every given vault of {@code owner} in one transaction.
     *
     * @param overwrite replace vaults that already exist instead of skipping the whole player
     */
    CompletableFuture<Result> importVaults(UUID owner, List<Vault> vaults, boolean overwrite);
}
