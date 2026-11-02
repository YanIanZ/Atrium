package dev.yanianz.atrium.module.kits;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Places kit items. Storage items go to their recorded slot when it is free, armor and offhand into their
 * equipment slot when free; everything else is added normally and, if it still does not fit, overflows.
 * All methods run on the receiving player's thread.
 */
final class KitGiver {
    /** The largest inventory {@link Bukkit#createInventory} accepts. */
    private static final int MAX_SIMULATED = 54;

    private KitGiver() {}

    /** Fills simulation slots that do not exist in the live storage; it never stacks with a kit item. */
    private static ItemStack blocker() {
        ItemStack blocker = new ItemStack(org.bukkit.Material.BARRIER);
        blocker.editMeta(meta -> meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey("atrium", "kit_simulation_blocker"),
                org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1));
        return blocker;
    }

    /**
     * True when the whole kit fits without dropping anything. Simulated on a copy; nothing is changed. The copy
     * has the live storage size (36 on a vanilla server, rounded up to whole rows for the simulation); rounding
     * slots are filled with a blocker so they never count as free space.
     */
    static boolean fits(PlayerInventory inventory, ItemStack[] kit) {
        ItemStack[] storage = inventory.getStorageContents();
        int live = Math.min(storage.length, MAX_SIMULATED);
        int size = Math.clamp((live + 8) / 9 * 9, 9, MAX_SIMULATED);
        Inventory copy = Bukkit.createInventory(null, size);
        for (int i = 0; i < live; i++) {
            copy.setItem(i, storage[i] == null ? null : storage[i].clone());
        }
        if (live < size) {
            ItemStack blocker = blocker();
            for (int i = live; i < size; i++) {
                copy.setItem(i, blocker.clone());
            }
        }
        List<ItemStack> rest = new ArrayList<>();
        for (int i = 0; i < KitItems.ARMOR_START; i++) {
            ItemStack item = kit[i];
            if (item == null || item.isEmpty()) continue;
            if (i < live && isEmpty(copy.getItem(i))) copy.setItem(i, item.clone());
            else rest.add(item.clone());
        }
        ItemStack[] armor = inventory.getArmorContents();
        for (int i = 0; i < 4; i++) {
            ItemStack item = kit[KitItems.ARMOR_START + i];
            if (item != null && !item.isEmpty() && (i >= armor.length || !isEmpty(armor[i]))) rest.add(item.clone());
        }
        ItemStack offhand = kit[KitItems.OFFHAND];
        if (offhand != null && !offhand.isEmpty() && !inventory.getItemInOffHand().isEmpty()) rest.add(offhand.clone());
        return rest.isEmpty() || copy.addItem(rest.toArray(ItemStack[]::new)).isEmpty();
    }

    /** Gives the kit; returns what did not fit (already dropped at the player's feet when {@code drop}). */
    static List<ItemStack> give(Player player, ItemStack[] kit, boolean drop) {
        PlayerInventory inventory = player.getInventory();
        // Kit slots 0-35 are storage slots; never address armour or off hand when the live storage is smaller.
        int storage = inventory.getStorageContents().length;
        List<ItemStack> rest = new ArrayList<>();
        for (int i = 0; i < KitItems.ARMOR_START; i++) {
            ItemStack item = kit[i];
            if (item == null || item.isEmpty()) continue;
            if (i < storage && isEmpty(inventory.getItem(i))) inventory.setItem(i, item.clone());
            else rest.add(item.clone());
        }
        ItemStack[] armor = inventory.getArmorContents();
        boolean armorChanged = false;
        for (int i = 0; i < 4; i++) {
            ItemStack item = kit[KitItems.ARMOR_START + i];
            if (item == null || item.isEmpty()) continue;
            if (i < armor.length && isEmpty(armor[i])) {
                armor[i] = item.clone();
                armorChanged = true;
            } else {
                rest.add(item.clone());
            }
        }
        if (armorChanged) inventory.setArmorContents(armor);
        ItemStack offhand = kit[KitItems.OFFHAND];
        if (offhand != null && !offhand.isEmpty()) {
            if (inventory.getItemInOffHand().isEmpty()) inventory.setItemInOffHand(offhand.clone());
            else rest.add(offhand.clone());
        }
        List<ItemStack> overflow = new ArrayList<>();
        if (!rest.isEmpty()) {
            overflow.addAll(inventory.addItem(rest.toArray(ItemStack[]::new)).values());
        }
        if (drop) {
            for (ItemStack item : overflow) {
                player.getWorld().dropItem(player.getLocation(), item, dropped -> dropped.setOwner(player.getUniqueId()));
            }
        }
        return overflow;
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.isEmpty();
    }
}
