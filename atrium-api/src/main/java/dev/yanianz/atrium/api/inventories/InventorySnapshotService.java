package dev.yanianz.atrium.api.inventories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Offline access to the inventory snapshots captured by the inventories module.
 *
 * <p>Contents use the stored snapshot layout. {@code inventory} is the storage part, then four armour slots
 * (boots to helmet), then the off hand: on a vanilla server that is {@value #INVENTORY_SLOTS} slots (0-35 storage,
 * 36-39 armour, 40 off hand). Use {@link Contents#storage()}, {@link Contents#armour()} and
 * {@link Contents#offhand()} instead of fixed indices, because a server fork may report a larger storage (at most
 * {@value #MAX_STORAGE_SLOTS} slots). {@code enderChest} has at least {@value #ENDER_SLOTS} slots and as many as
 * the ender chest had when the snapshot was taken (Paper forks and plugins allow up to
 * {@value #MAX_ENDER_SLOTS}). Every list is padded with empty slots and copied, so a caller may keep or modify its
 * copy without touching the snapshot history.
 *
 * <p>All methods are safe to call from any thread and never block a game thread on storage. An offline edit
 * never moves items between the administrator and the target: it replaces the target's contents with the
 * edited copy, and the administrator never receives any of the target's items. When the live inventory is
 * smaller than the edited copy, the items past the live slot counts are added to the inventory or dropped at
 * the player's feet, never deleted.
 */
public interface InventorySnapshotService {
    /** Vanilla inventory layout and minimum list size: storage (0-35), armour (36-39) and off hand (40). */
    int INVENTORY_SLOTS = 41;
    /** One vanilla ender chest page: the minimum ender list size. */
    int ENDER_SLOTS = 27;
    /** Armour slots between the storage part and the off hand. */
    int ARMOUR_SLOTS = 4;
    /** Largest storage part accepted (six rows on a fork that enlarges it). */
    int MAX_STORAGE_SLOTS = 54;
    /** Largest inventory list: {@link #MAX_STORAGE_SLOTS} storage + 4 armour + off hand. */
    int MAX_INVENTORY_SLOTS = MAX_STORAGE_SLOTS + ARMOUR_SLOTS + 1;
    /** Largest ender chest (six rows). */
    int MAX_ENDER_SLOTS = 54;

    /**
     * A full inventory and ender chest state. The lists are padded to at least the vanilla slot counts and are
     * private copies; {@code null} and empty stacks are both treated as empty slots. Larger lists are kept at
     * their size up to {@link #MAX_INVENTORY_SLOTS} and {@link #MAX_ENDER_SLOTS}; anything larger is refused.
     */
    record Contents(List<ItemStack> inventory, List<ItemStack> enderChest) {
        public Contents {
            inventory = slotCopies(inventory, INVENTORY_SLOTS, MAX_INVENTORY_SLOTS, "inventory");
            enderChest = slotCopies(enderChest, ENDER_SLOTS, MAX_ENDER_SLOTS, "ender chest");
        }

        /** Number of storage slots: everything before armour and off hand (36 on a vanilla server). */
        public int storageSlots() {
            return storageSlots(inventory);
        }

        /** The storage part: hotbar and main inventory. */
        public List<ItemStack> storage() {
            return inventory.subList(0, storageSlots());
        }

        /** Armour slots: boots, leggings, chestplate, helmet. */
        public List<ItemStack> armour() {
            return inventory.subList(storageSlots(), storageSlots() + ARMOUR_SLOTS);
        }

        public @Nullable ItemStack offhand() {
            return inventory.get(inventory.size() - 1);
        }

        static int storageSlots(List<ItemStack> inventory) {
            return inventory.size() - ARMOUR_SLOTS - 1;
        }

        private static List<ItemStack> slotCopies(@Nullable List<ItemStack> items, int min, int max,
                String section) {
            List<ItemStack> source = items == null ? List.of() : items;
            if (source.size() > max) {
                throw new IllegalArgumentException(section + " has " + source.size()
                        + " slots but a snapshot stores at most " + max);
            }
            int slots = Math.max(min, source.size());
            List<ItemStack> copy = new ArrayList<>(slots);
            for (int slot = 0; slot < slots; slot++) {
                ItemStack item = slot < source.size() ? source.get(slot) : null;
                copy.add(item == null || item.isEmpty() ? null : item.clone());
            }
            return Collections.unmodifiableList(copy);
        }
    }

    /** An immutable snapshot read from the history. {@code reason} is a short code such as {@code quit}. */
    record Snapshot(long id, UUID player, String playerName, long createdAt, String reason,
            List<ItemStack> inventory, List<ItemStack> enderChest) {
        public Snapshot {
            Contents contents = new Contents(inventory, enderChest);
            inventory = contents.inventory();
            enderChest = contents.enderChest();
        }

        public Contents contents() {
            return new Contents(inventory, enderChest);
        }

        /** The storage part of the inventory (slots 0-35 on a vanilla server). */
        public List<ItemStack> storage() {
            return inventory.subList(0, Contents.storageSlots(inventory));
        }

        /** Armour slots: boots, leggings, chestplate, helmet. */
        public List<ItemStack> armour() {
            int from = Contents.storageSlots(inventory);
            return inventory.subList(from, from + ARMOUR_SLOTS);
        }

        public @Nullable ItemStack offhand() {
            return inventory.get(inventory.size() - 1);
        }
    }

    /** A stored offline edit that is waiting for the target to join. */
    record PendingEdit(long id, UUID player, long baseSnapshotId, long createdAt) {}

    /**
     * The newest snapshot of a player, optionally restricted to one reason (for example {@code "quit"}). The
     * comparison is case-insensitive; an unknown reason completes with an empty result instead of guessing.
     */
    CompletableFuture<Optional<Snapshot>> latest(UUID player, @Nullable String reason);

    /**
     * Stores an edited copy of a snapshot as a pending edit. The target's next {@code PlayerJoinEvent} applies
     * it only while their contents are still identical to the base snapshot; otherwise the edit is kept in the
     * snapshot history and staff are notified. Empty when the base snapshot does not belong to the player or
     * the player is already online (use live /invsee for them).
     */
    CompletableFuture<Optional<PendingEdit>> schedulePendingEdit(UUID player, long baseSnapshotId, Contents contents);

    /**
     * Same as {@link #schedulePendingEdit(UUID, long, Contents)} but records who prepared the edit, for the
     * snapshot history and the staff notification.
     */
    default CompletableFuture<Optional<PendingEdit>> schedulePendingEdit(UUID player, long baseSnapshotId,
            Contents contents, @Nullable UUID editor, @Nullable String editorName) {
        return schedulePendingEdit(player, baseSnapshotId, contents);
    }
}
