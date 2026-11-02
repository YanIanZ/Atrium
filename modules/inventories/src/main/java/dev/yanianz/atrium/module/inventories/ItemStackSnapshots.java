package dev.yanianz.atrium.module.inventories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Converts live players to/from stored snapshots with Paper's {@code serializeItemsAsBytes}. Everything here
 * touches a player and must run on that player's own region thread.
 */
final class ItemStackSnapshots {
    static final SnapshotCodec.ItemCodec<ItemStack> CODEC = new SnapshotCodec.ItemCodec<>() {
        @Override
        public byte[] encode(List<ItemStack> items) {
            return ItemStack.serializeItemsAsBytes(items.toArray(new ItemStack[items.size()]));
        }

        @Override
        public List<ItemStack> decode(byte[] data) {
            ItemStack[] decoded = ItemStack.deserializeItemsFromBytes(data);
            List<ItemStack> items = new ArrayList<>(decoded.length);
            Collections.addAll(items, decoded);
            return items;
        }
    };

    /**
     * A capture of one player: the stored bytes plus copies of the captured slots, so the live contents can be
     * compared with the backup right before a destructive change.
     */
    record Captured(byte[] bytes, int stacks, List<ItemStack> inventory, List<ItemStack> ender) {
        Captured {
            inventory = Collections.unmodifiableList(copies(inventory));
            ender = Collections.unmodifiableList(copies(ender));
        }

        /**
         * Storage, armour, offhand and ender chest as separate lists, so two captures with a different storage
         * or ender size are compared section by section (missing tail slots count as empty).
         */
        List<List<ItemStack>> sections() {
            SnapshotCodec.Contents<ItemStack> contents = new SnapshotCodec.Contents<>(inventory, ender);
            return List.of(contents.storage(), contents.armour(), contents.offhand(), contents.enderChest());
        }
    }

    /**
     * What a load did. {@code overflow} counts the stacks that did not fit the live slot they came from (they
     * went into free inventory space); {@code dropped} counts the ones that were then dropped at the player's
     * feet because the inventory was full.
     */
    record Applied(boolean applied, int overflow, int dropped) {
        static final Applied REFUSED = new Applied(false, 0, 0);
    }

    /** Unchecked wrapper for an in-memory serialization failure (a runtime call cannot throw checked). */
    static final class SnapshotFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SnapshotFailure(SnapshotCodec.SnapshotFormatException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private record Merged(ItemStack[] storage, ItemStack[] ender, int spilled) {}

    private ItemStackSnapshots() {}

    static Captured capture(Player player) {
        try {
            List<ItemStack> inventory = inventorySlots(player.getInventory());
            List<ItemStack> ender = Arrays.asList(player.getEnderChest().getContents());
            byte[] bytes = SnapshotCodec.encode(new SnapshotCodec.Contents<>(inventory, ender), CODEC);
            return new Captured(bytes, countStacks(inventory) + countStacks(ender), inventory, ender);
        } catch (SnapshotCodec.SnapshotFormatException e) {
            throw new SnapshotFailure(e);
        }
    }

    /**
     * Captures the player again and tells whether {@code backup} still matches. {@code enderOnly} compares only the
     * ender chest (for changes that only touch it). Call on the player's thread.
     */
    static boolean unchanged(Player player, Captured backup, boolean enderOnly) {
        Captured now = capture(player);
        if (enderOnly) {
            return BackupGuard.sameSlots(backup.ender(), now.ender(), ItemStack::isEmpty);
        }
        return matches(backup, now);
    }

    /** True when {@code now} still holds exactly the items of {@code base}, section by section. */
    static boolean matches(Captured base, Captured now) {
        return BackupGuard.unchanged(base.bytes(), now.bytes(), base.sections(), now.sections(),
                ItemStack::isEmpty);
    }

    static SnapshotCodec.Contents<ItemStack> decode(byte[] bytes) throws SnapshotCodec.SnapshotFormatException {
        return SnapshotCodec.decode(bytes, CODEC);
    }

    /** True when every item of the snapshot fits into the current storage and ender chest. */
    static boolean canMerge(Player player, SnapshotCodec.Contents<ItemStack> contents) {
        return merged(player, contents) != null;
    }

    /**
     * Replaces or merges the stored contents into the live inventory sizes.
     *
     * <p>A replace never loses an item: whatever does not fit a smaller live section (for example a six-row
     * ender snapshot loaded into a three-row ender chest) is added to the player inventory, and what still does
     * not fit is dropped at the player's feet. A merge is simulated first: ender items that do not fit the live
     * ender chest spill into the storage, and when anything still would not fit, nothing is changed and
     * {@link Applied#REFUSED} is returned.
     */
    static Applied apply(Player player, SnapshotCodec.Contents<ItemStack> contents, boolean merge) {
        if (!merge) {
            return replace(player, contents);
        }
        Merged merged = merged(player, contents);
        if (merged == null) {
            return Applied.REFUSED;
        }
        player.getInventory().setStorageContents(merged.storage());
        player.getEnderChest().setContents(merged.ender());
        return new Applied(true, merged.spilled(), 0);
    }

    private static Applied replace(Player player, SnapshotCodec.Contents<ItemStack> contents) {
        PlayerInventory inventory = player.getInventory();
        Inventory enderChest = player.getEnderChest();
        RestorePlan.Sizes live = new RestorePlan.Sizes(inventory.getStorageContents().length,
                inventory.getArmorContents().length, 1, enderChest.getSize());
        RestorePlan.Plan<ItemStack> plan = RestorePlan.plan(contents, live, ItemStackSnapshots::empty);
        inventory.setStorageContents(array(plan.storage(), live.storage()));
        inventory.setArmorContents(array(plan.armour(), live.armour()));
        inventory.setItemInOffHand(copyAt(plan.offhand(), 0));
        enderChest.setContents(array(plan.ender(), live.ender()));
        int dropped = 0;
        for (ItemStack stack : copies(plan.overflow())) {
            if (stack == null) {
                continue;
            }
            for (ItemStack leftover : inventory.addItem(stack).values()) {
                if (!empty(leftover)) {
                    player.getWorld().dropItem(player.getLocation(), leftover);
                    dropped++;
                }
            }
        }
        return new Applied(true, plan.overflow().size(), dropped);
    }

    /**
     * Simulates the merge on copies. Returns {@code null} when at least one stack would not fit. Only the storage
     * slots are written, so armour and offhand are never overwritten; armour and offhand items of the snapshot
     * are merged into the storage like any other item, and ender items that do not fit the live ender chest
     * spill into the storage too. Nothing is ever dropped by a merge.
     */
    private static Merged merged(Player player, SnapshotCodec.Contents<ItemStack> contents) {
        ItemStack[] storage = clones(Arrays.asList(player.getInventory().getStorageContents()));
        if (!fill(storage, contents.inventory()).isEmpty()) {
            return null;
        }
        ItemStack[] ender = clones(Arrays.asList(player.getEnderChest().getContents()));
        List<ItemStack> spill = fill(ender, contents.enderChest());
        if (!fill(storage, spill).isEmpty()) {
            return null;
        }
        return new Merged(storage, ender, spill.size());
    }

    /**
     * Adds copies of {@code additions} to {@code slots} like an inventory would: first onto similar partial
     * stacks, then into empty slots, never above an item's maximum stack size. Returns what did not fit.
     */
    private static List<ItemStack> fill(ItemStack[] slots, List<ItemStack> additions) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack addition : additions) {
            if (empty(addition)) {
                continue;
            }
            ItemStack stack = addition.clone();
            int remaining = stack.getAmount();
            int max = Math.max(1, stack.getMaxStackSize());
            for (int slot = 0; slot < slots.length && remaining > 0; slot++) {
                ItemStack existing = slots[slot];
                if (empty(existing) || !existing.isSimilar(stack)) {
                    continue;
                }
                int room = Math.max(1, existing.getMaxStackSize()) - existing.getAmount();
                if (room > 0) {
                    int moved = Math.min(room, remaining);
                    existing.setAmount(existing.getAmount() + moved);
                    remaining -= moved;
                }
            }
            for (int slot = 0; slot < slots.length && remaining > 0; slot++) {
                if (!empty(slots[slot])) {
                    continue;
                }
                int moved = Math.min(max, remaining);
                ItemStack placed = stack.clone();
                placed.setAmount(moved);
                slots[slot] = placed;
                remaining -= moved;
            }
            if (remaining > 0) {
                ItemStack leftover = stack.clone();
                leftover.setAmount(remaining);
                leftovers.add(leftover);
            }
        }
        return leftovers;
    }

    /**
     * Storage, armour (always 4 slots) and offhand of the live inventory, in the stored layout. The sections are
     * read separately, so a storage size other than 36 still keeps armour and offhand at the right place.
     */
    private static List<ItemStack> inventorySlots(PlayerInventory inventory) throws SnapshotCodec.SnapshotFormatException {
        ItemStack[] storage = inventory.getStorageContents();
        ItemStack[] armour = inventory.getArmorContents();
        if (storage.length > SnapshotCodec.MAX_STORAGE_SLOTS) {
            throw new SnapshotCodec.SnapshotFormatException("storage has " + storage.length
                    + " slots (at most " + SnapshotCodec.MAX_STORAGE_SLOTS + " are supported)");
        }
        if (armour.length > SnapshotCodec.ARMOUR_SLOTS) {
            throw new SnapshotCodec.SnapshotFormatException("armour has " + armour.length
                    + " slots (at most " + SnapshotCodec.ARMOUR_SLOTS + " are supported)");
        }
        int storageSlots = Math.max(SnapshotCodec.VANILLA_STORAGE_SLOTS, storage.length);
        List<ItemStack> slots = new ArrayList<>(storageSlots + SnapshotCodec.EQUIPMENT_SLOTS);
        for (int slot = 0; slot < storageSlots; slot++) {
            slots.add(slot < storage.length ? storage[slot] : null);
        }
        for (int slot = 0; slot < SnapshotCodec.ARMOUR_SLOTS; slot++) {
            slots.add(slot < armour.length ? armour[slot] : null);
        }
        slots.add(inventory.getItemInOffHand());
        return slots;
    }

    static List<ItemStack> copies(List<ItemStack> items) {
        List<ItemStack> copy = new ArrayList<>(items.size());
        for (ItemStack item : items) {
            copy.add(copy(item));
        }
        return copy;
    }

    static ItemStack[] array(List<ItemStack> items, int size) {
        ItemStack[] result = new ItemStack[Math.max(0, size)];
        for (int slot = 0; slot < result.length; slot++) {
            result[slot] = copyAt(items, slot);
        }
        return result;
    }

    private static ItemStack copyAt(List<ItemStack> items, int slot) {
        return slot >= 0 && slot < items.size() ? copy(items.get(slot)) : null;
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }

    private static ItemStack[] clones(List<ItemStack> items) {
        ItemStack[] copy = new ItemStack[items.size()];
        for (int slot = 0; slot < copy.length; slot++) {
            copy[slot] = copy(items.get(slot));
        }
        return copy;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.isEmpty();
    }

    static int countStacks(List<ItemStack> items) {
        int count = 0;
        for (ItemStack item : items) {
            if (!empty(item)) {
                count++;
            }
        }
        return count;
    }
}
