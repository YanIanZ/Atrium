package dev.yanianz.atrium.module.inventories;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
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
    }

    /** Unchecked wrapper for an in-memory serialization failure (a runtime call cannot throw checked). */
    static final class SnapshotFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SnapshotFailure(SnapshotCodec.SnapshotFormatException cause) {
            super(cause.getMessage(), cause);
        }
    }

    private record Merged(ItemStack[] storage, ItemStack[] ender) {}

    private ItemStackSnapshots() {}

    static Captured capture(Player player) {
        try {
            List<ItemStack> inventory = Arrays.asList(inventorySlots(player.getInventory()));
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
        return BackupGuard.unchanged(backup.bytes(), now.bytes(), List.of(backup.inventory(), backup.ender()),
                List.of(now.inventory(), now.ender()), ItemStack::isEmpty);
    }

    static SnapshotCodec.Contents<ItemStack> decode(byte[] bytes) throws SnapshotCodec.SnapshotFormatException {
        return SnapshotCodec.decode(bytes, CODEC);
    }

    /** True when every item of the snapshot fits into the current storage and ender chest. */
    static boolean canMerge(Player player, SnapshotCodec.Contents<ItemStack> contents) {
        return merged(player, contents) != null;
    }

    /**
     * Replaces or merges the stored contents. A merge is simulated in temporary inventories first, so a full
     * inventory never loses an item: when it would not fit, nothing is changed and {@code false} is returned.
     */
    static boolean apply(Player player, SnapshotCodec.Contents<ItemStack> contents, boolean merge) {
        if (!merge) {
            replace(player, contents);
            return true;
        }
        Merged merged = merged(player, contents);
        if (merged == null) {
            return false;
        }
        ItemStack[] current = player.getInventory().getContents();
        System.arraycopy(merged.storage(), 0, current, 0, Math.min(merged.storage().length, current.length));
        player.getInventory().setContents(current);
        player.getEnderChest().setContents(merged.ender());
        return true;
    }

    private static void replace(Player player, SnapshotCodec.Contents<ItemStack> contents) {
        PlayerInventory inventory = player.getInventory();
        int size = Math.max(0, inventory.getSize());
        ItemStack[] target = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            target[slot] = copyAt(contents.inventory(), slot);
        }
        inventory.setContents(target);
        // Servers that keep the offhand outside getContents() still receive it.
        if (size <= 40) {
            inventory.setItemInOffHand(copyAt(contents.inventory(), 40));
        }
        player.getEnderChest().setContents(array(contents.enderChest(), player.getEnderChest().getSize()));
    }

    /**
     * Simulates the merge in temporary inventories. Returns {@code null} when at least one stack would not
     * fit. Only the 36 storage slots are written, so armour and offhand are never overwritten; armour and
     * offhand items of the snapshot are merged into the storage like any other item and are never dropped.
     */
    private static Merged merged(Player player, SnapshotCodec.Contents<ItemStack> contents) {
        PlayerInventory inventory = player.getInventory();
        int storageSize = Math.min(36, Math.max(0, inventory.getSize()));
        ItemStack[] current = inventory.getContents();
        ItemStack[] storage = simulate(Arrays.copyOf(current, storageSize), storageSize, contents.inventory());
        if (storage == null) {
            return null;
        }
        int enderSize = player.getEnderChest().getSize();
        ItemStack[] ender = simulate(player.getEnderChest().getContents(), enderSize, contents.enderChest());
        return ender == null ? null : new Merged(storage, ender);
    }

    private static ItemStack[] simulate(ItemStack[] current, int size, List<ItemStack> additions) {
        if (size <= 0) {
            return additions.stream().allMatch(ItemStackSnapshots::empty) ? new ItemStack[0] : null;
        }
        Inventory simulation = Bukkit.createInventory(null, size);
        ItemStack[] filled = new ItemStack[size];
        for (int slot = 0; slot < size; slot++) {
            filled[slot] = slot < current.length ? copy(current[slot]) : null;
        }
        simulation.setContents(filled);
        Map<Integer, ItemStack> leftover = simulation.addItem(clones(additions));
        return leftover.isEmpty() ? simulation.getContents() : null;
    }

    private static ItemStack[] inventorySlots(PlayerInventory inventory) {
        ItemStack[] raw = inventory.getContents();
        ItemStack[] slots = new ItemStack[SnapshotCodec.INVENTORY_SLOTS];
        System.arraycopy(raw, 0, slots, 0, Math.min(raw.length, SnapshotCodec.INVENTORY_SLOTS));
        // Defensive: keep the offhand even if a server build reports it outside getContents().
        if (raw.length <= 40) {
            slots[40] = inventory.getItemInOffHand();
        }
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
