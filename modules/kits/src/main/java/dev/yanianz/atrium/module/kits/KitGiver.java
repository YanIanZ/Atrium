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
    private KitGiver() {}

    /** True when the whole kit fits without dropping anything. Simulated on a copy; nothing is changed. */
    static boolean fits(PlayerInventory inventory, ItemStack[] kit) {
        Inventory copy = Bukkit.createInventory(null, 36);
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < Math.min(36, storage.length); i++) {
            copy.setItem(i, storage[i] == null ? null : storage[i].clone());
        }
        List<ItemStack> rest = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = kit[i];
            if (item == null || item.isEmpty()) continue;
            if (isEmpty(copy.getItem(i))) copy.setItem(i, item.clone());
            else rest.add(item.clone());
        }
        ItemStack[] armor = inventory.getArmorContents();
        for (int i = 0; i < 4; i++) {
            ItemStack item = kit[KitItems.ARMOR_START + i];
            if (item != null && !item.isEmpty() && !isEmpty(armor[i])) rest.add(item.clone());
        }
        ItemStack offhand = kit[KitItems.OFFHAND];
        if (offhand != null && !offhand.isEmpty() && !inventory.getItemInOffHand().isEmpty()) rest.add(offhand.clone());
        return rest.isEmpty() || copy.addItem(rest.toArray(ItemStack[]::new)).isEmpty();
    }

    /** Gives the kit; returns what did not fit (already dropped at the player's feet when {@code drop}). */
    static List<ItemStack> give(Player player, ItemStack[] kit, boolean drop) {
        PlayerInventory inventory = player.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack item = kit[i];
            if (item == null || item.isEmpty()) continue;
            if (isEmpty(inventory.getItem(i))) inventory.setItem(i, item.clone());
            else rest.add(item.clone());
        }
        ItemStack[] armor = inventory.getArmorContents();
        boolean armorChanged = false;
        for (int i = 0; i < 4; i++) {
            ItemStack item = kit[KitItems.ARMOR_START + i];
            if (item == null || item.isEmpty()) continue;
            if (isEmpty(armor[i])) {
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
