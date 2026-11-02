package dev.yanianz.atrium.module.itemtools;

import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.Repairable;

/** Small item helpers shared by the commands. Server-side code only (ItemStack needs the running server). */
final class ItemValues {
    private ItemValues() {
    }

    /** A plain stack: no name, lore, enchantment, CustomModelData, PDC, flags, damage or repair cost. */
    static boolean plain(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return true;
        }
        if (meta.hasCustomName() || meta.hasLore() || meta.hasEnchants() || meta.hasCustomModelDataComponent()
                || meta.isUnbreakable() || meta.hasAttributeModifiers() || meta.hasEnchantmentGlintOverride()
                || meta.isHideTooltip() || meta.hasTooltipStyle() || meta.hasRarity()
                || !meta.getPersistentDataContainer().isEmpty() || !meta.getItemFlags().isEmpty()) {
            return false;
        }
        if (meta instanceof EnchantmentStorageMeta storage && storage.hasStoredEnchants()) {
            return false;
        }
        if (meta instanceof ArmorMeta armor && armor.hasTrim()) {
            return false;
        }
        if (meta instanceof Damageable damageable && damageable.hasDamage()) {
            return false;
        }
        return !(meta instanceof Repairable repairable) || !repairable.hasRepairCost();
    }

    /** The components hidden by /hideflags, matching the old ItemFlag tooltip set. */
    static Set<DataComponentType> hiddenComponents() {
        Set<DataComponentType> hidden = new HashSet<>();
        hidden.add(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        hidden.add(DataComponentTypes.ENCHANTMENTS);
        hidden.add(DataComponentTypes.STORED_ENCHANTMENTS);
        hidden.add(DataComponentTypes.UNBREAKABLE);
        hidden.add(DataComponentTypes.CAN_BREAK);
        hidden.add(DataComponentTypes.CAN_PLACE_ON);
        hidden.add(DataComponentTypes.DYED_COLOR);
        hidden.add(DataComponentTypes.TRIM);
        hidden.add(DataComponentTypes.POTION_CONTENTS);
        hidden.add(DataComponentTypes.FIREWORK_EXPLOSION);
        hidden.add(DataComponentTypes.BANNER_PATTERNS);
        return Set.copyOf(hidden);
    }

    /** Splits an amount into stacks that respect the material's maximum stack size. */
    static List<ItemStack> stacks(Material material, int amount) {
        if (amount <= 0) {
            return List.of();
        }
        int max = Math.max(1, material.getMaxStackSize());
        java.util.ArrayList<ItemStack> stacks = new java.util.ArrayList<>();
        int remaining = amount;
        while (remaining > 0) {
            int size = Math.min(max, remaining);
            stacks.add(new ItemStack(material, size));
            remaining -= size;
        }
        return stacks;
    }

    /** Adds items, dropping only the real overflow at the player's feet. Returns the dropped amount. */
    static int giveOrDrop(Player player, List<ItemStack> items) {
        if (items.isEmpty()) {
            return 0;
        }
        var leftovers = player.getInventory().addItem(items.toArray(ItemStack[]::new));
        int dropped = 0;
        for (ItemStack rest : leftovers.values()) {
            if (rest == null || rest.isEmpty()) {
                continue;
            }
            player.getWorld().dropItem(player.getLocation(), rest);
            dropped += rest.getAmount();
        }
        return dropped;
    }

    /** Safe component for a possibly player-named item. */
    static Component name(ItemStack item) {
        return item.effectiveName();
    }

    /** Lower-case material ids for tab completion. */
    static List<String> itemNames() {
        return Arrays.stream(Material.values())
                .filter(Material::isItem)
                .filter(material -> !material.isLegacy())
                .map(material -> material.getKey().getKey().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }
}
