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
 * <p>Contents always use the stored snapshot layout: {@code inventory} has
 * {@value #INVENTORY_SLOTS} slots (0-35 storage, 36-39 armour from boots to helmet, 40 off hand) and
 * {@code enderChest} has {@value #ENDER_SLOTS} slots. Every list is padded with empty slots and copied, so a
 * caller may keep or modify its copy without touching the snapshot history.
 *
 * <p>All methods are safe to call from any thread and never block a game thread on storage. An offline edit
 * never moves items between the administrator and the target: it replaces the target's contents with the
 * edited copy, and the administrator never receives any of the target's items.
 */
public interface InventorySnapshotService {
    /** Storage (0-35), armour (36-39) and off hand (40) in one list. */
    int INVENTORY_SLOTS = 41;
    /** One ender chest page. */
    int ENDER_SLOTS = 27;

    /**
     * A full inventory and ender chest state. The lists are padded to the fixed slot counts and are private
     * copies; {@code null} and empty stacks are both treated as empty slots.
     */
    record Contents(List<ItemStack> inventory, List<ItemStack> enderChest) {
        public Contents {
            inventory = slotCopies(inventory, INVENTORY_SLOTS, "inventory");
            enderChest = slotCopies(enderChest, ENDER_SLOTS, "ender chest");
        }

        /** Storage slots 0-35. */
        public List<ItemStack> storage() {
            return inventory.subList(0, 36);
        }

        /** Armour slots: boots, leggings, chestplate, helmet. */
        public List<ItemStack> armour() {
            return inventory.subList(36, 40);
        }

        public @Nullable ItemStack offhand() {
            return inventory.get(40);
        }

        private static List<ItemStack> slotCopies(@Nullable List<ItemStack> items, int slots, String section) {
            List<ItemStack> source = items == null ? List.of() : items;
            if (source.size() > slots) {
                throw new IllegalArgumentException(section + " has " + source.size()
                        + " slots but a snapshot stores " + slots);
            }
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

        /** Storage slots 0-35 of the inventory. */
        public List<ItemStack> storage() {
            return inventory.subList(0, 36);
        }

        /** Armour slots: boots, leggings, chestplate, helmet. */
        public List<ItemStack> armour() {
            return inventory.subList(36, 40);
        }

        public @Nullable ItemStack offhand() {
            return inventory.get(40);
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
