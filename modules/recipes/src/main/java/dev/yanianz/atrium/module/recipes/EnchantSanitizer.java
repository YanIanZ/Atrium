package dev.yanianz.atrium.module.recipes;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

/**
 * Applies {@link EnchantRules} to an item, handling both regular enchantments and the stored enchantments of
 * an enchanted book. A disabled enchantment is removed, never forced to level 1.
 */
public final class EnchantSanitizer {
    /**
     * @param item          the original item when unchanged, otherwise a sanitized clone
     * @param changed       whether any enchantment was removed or capped
     * @param disabledFound whether a disabled enchantment was present (used by the "clear result" mode)
     */
    public record Result(@Nullable ItemStack item, boolean changed, boolean disabledFound) {}

    private EnchantSanitizer() {}

    public static Result sanitize(@Nullable ItemStack original, EnchantRules rules) {
        if (original == null || original.getType().isAir() || !original.hasItemMeta()) {
            return new Result(original, false, false);
        }
        ItemStack item = original.clone();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return new Result(original, false, false);
        }
        boolean changed = false;
        boolean disabled = false;

        for (Map.Entry<Enchantment, Integer> entry : new HashMap<>(meta.getEnchants()).entrySet()) {
            int allowed = rules.allowedLevel(key(entry.getKey()), entry.getValue());
            if (allowed <= 0) {
                meta.removeEnchant(entry.getKey());
                changed = true;
                disabled = true;
            } else if (allowed != entry.getValue()) {
                meta.removeEnchant(entry.getKey());
                meta.addEnchant(entry.getKey(), allowed, true);
                changed = true;
            }
        }
        if (meta instanceof EnchantmentStorageMeta storage) {
            for (Map.Entry<Enchantment, Integer> entry : new HashMap<>(storage.getStoredEnchants()).entrySet()) {
                int allowed = rules.allowedLevel(key(entry.getKey()), entry.getValue());
                if (allowed <= 0) {
                    storage.removeStoredEnchant(entry.getKey());
                    changed = true;
                    disabled = true;
                } else if (allowed != entry.getValue()) {
                    storage.removeStoredEnchant(entry.getKey());
                    storage.addStoredEnchant(entry.getKey(), allowed, true);
                    changed = true;
                }
            }
        }
        if (!changed) {
            return new Result(original, false, disabled);
        }
        if (!item.setItemMeta(meta)) {
            return new Result(original, false, disabled);
        }
        return new Result(item, true, disabled);
    }

    public static boolean hasAnyEnchant(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        if (!meta.getEnchants().isEmpty()) {
            return true;
        }
        return meta instanceof EnchantmentStorageMeta storage && !storage.getStoredEnchants().isEmpty();
    }

    private static String key(Enchantment enchantment) {
        return enchantment.getKey().toString();
    }
}
