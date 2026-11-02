package dev.yanianz.atrium.module.importer;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Turns a {@link KitItemSpec} into an {@code ItemStack}. Needs the server registries, so it runs on the
 * global region thread at import time; the alias table is pure and tested on its own.
 */
final class KitItemFactory {
    /** Old Bukkit enchantment names and EssentialsX's short aliases to vanilla keys. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("damage_all", "sharpness"), Map.entry("sharp", "sharpness"), Map.entry("sharpness", "sharpness"),
            Map.entry("damage_undead", "smite"), Map.entry("damage_arthropods", "bane_of_arthropods"),
            Map.entry("bane", "bane_of_arthropods"), Map.entry("dig_speed", "efficiency"), Map.entry("eff", "efficiency"),
            Map.entry("durability", "unbreaking"), Map.entry("unbreak", "unbreaking"),
            Map.entry("loot_bonus_blocks", "fortune"), Map.entry("loot_bonus_mobs", "looting"),
            Map.entry("loot", "looting"), Map.entry("silktouch", "silk_touch"), Map.entry("silk", "silk_touch"),
            Map.entry("protection_environmental", "protection"), Map.entry("prot", "protection"),
            Map.entry("protection_fire", "fire_protection"), Map.entry("protection_fall", "feather_falling"),
            Map.entry("featherfalling", "feather_falling"), Map.entry("protection_explosions", "blast_protection"),
            Map.entry("protection_projectile", "projectile_protection"), Map.entry("oxygen", "respiration"),
            Map.entry("water_worker", "aqua_affinity"), Map.entry("arrow_damage", "power"),
            Map.entry("arrow_knockback", "punch"), Map.entry("arrow_fire", "flame"), Map.entry("arrow_infinite", "infinity"),
            Map.entry("infinite", "infinity"), Map.entry("fireaspect", "fire_aspect"), Map.entry("luck", "luck_of_the_sea"),
            Map.entry("depthstrider", "depth_strider"), Map.entry("frostwalker", "frost_walker"),
            Map.entry("sweeping", "sweeping_edge"), Map.entry("sweeping_edge", "sweeping_edge"));

    private KitItemFactory() {}

    /** The vanilla key for an enchantment written in any of the supported spellings. */
    static String enchantmentKey(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        return ALIASES.getOrDefault(key, key);
    }

    /** Outcome of building one item: the stack, or the reason it could not be built, plus dropped data. */
    record Built(ItemStack item, String problem, List<String> dropped) {}

    static Built build(KitItemSpec spec) {
        if (!spec.ok()) {
            return new Built(null, spec.problem(), List.of());
        }
        if (spec.serialized() != null) {
            try {
                ItemStack item = ItemStack.deserializeBytes(spec.serialized());
                return item.isEmpty() ? new Built(null, "serialized item is empty", List.of())
                        : new Built(item, null, spec.unsupported());
            } catch (RuntimeException e) {
                return new Built(null, "serialized item cannot be read: " + e.getMessage(), List.of());
            }
        }
        Material material = spec.material() == null ? null : Material.matchMaterial(spec.material());
        if (material == null || !material.isItem() || material.isAir()) {
            return new Built(null, "unknown item '" + spec.material() + "'", List.of());
        }
        ItemStack item = new ItemStack(material, Math.max(1, Math.min(spec.amount(), material.getMaxStackSize())));
        List<String> dropped = new ArrayList<>(spec.unsupported());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (spec.name() != null && !spec.name().isBlank()) {
                meta.displayName(legacy(spec.name()));
            }
            if (!spec.lore().isEmpty()) {
                meta.lore(spec.lore().stream().map(KitItemFactory::legacy).toList());
            }
            var registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            for (var entry : spec.enchantments().entrySet()) {
                Enchantment enchantment = registry.get(NamespacedKey.minecraft(enchantmentKey(entry.getKey())));
                if (enchantment == null) {
                    dropped.add("enchantment " + entry.getKey());
                } else {
                    meta.addEnchant(enchantment, entry.getValue(), true);
                }
            }
            item.setItemMeta(meta);
        }
        return new Built(item, null, dropped);
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text.replace('§', '&'));
    }
}
