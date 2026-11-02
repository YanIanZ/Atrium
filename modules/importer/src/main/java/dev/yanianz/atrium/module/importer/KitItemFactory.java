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

    /** EssentialsX's enchantment names in kit lines (alias, vanilla key pairs), as its kits accept them. */
    private static final String[] ESSENTIALS_ENCHANTMENTS = {
            "alldamage", "sharpness", "alldmg", "sharpness", "sharp", "sharpness", "dal", "sharpness",
            "ardmg", "bane_of_arthropods", "baneofarthropods", "bane_of_arthropods", "baneofarthropod", "bane_of_arthropods", "arthropod", "bane_of_arthropods",
            "dar", "bane_of_arthropods", "undeaddamage", "smite", "du", "smite", "digspeed", "efficiency",
            "minespeed", "efficiency", "cutspeed", "efficiency", "ds", "efficiency", "eff", "efficiency",
            "durability", "unbreaking", "dura", "unbreaking", "d", "unbreaking", "highcrit", "thorns",
            "thorn", "thorns", "highercrit", "thorns", "t", "thorns", "fireaspect", "fire_aspect",
            "fire", "fire_aspect", "meleefire", "fire_aspect", "meleeflame", "fire_aspect", "fa", "fire_aspect",
            "kback", "knockback", "kb", "knockback", "k", "knockback", "blockslootbonus", "fortune",
            "fort", "fortune", "lbb", "fortune", "mobslootbonus", "looting", "mobloot", "looting",
            "lbm", "looting", "oxygen", "respiration", "breathing", "respiration", "breath", "respiration",
            "o", "respiration", "prot", "protection", "protect", "protection", "p", "protection",
            "explosionsprotection", "blast_protection", "explosionprotection", "blast_protection", "expprot", "blast_protection", "blastprotection", "blast_protection",
            "bprotection", "blast_protection", "bprotect", "blast_protection", "blastprotect", "blast_protection", "pe", "blast_protection",
            "fallprotection", "feather_falling", "fallprot", "feather_falling", "featherfall", "feather_falling", "featherfalling", "feather_falling",
            "pfa", "feather_falling", "fireprotection", "fire_protection", "flameprotection", "fire_protection", "fireprotect", "fire_protection",
            "flameprotect", "fire_protection", "fireprot", "fire_protection", "flameprot", "fire_protection", "pf", "fire_protection",
            "projectileprotection", "projectile_protection", "projprot", "projectile_protection", "pp", "projectile_protection", "silktouch", "silk_touch",
            "softtouch", "silk_touch", "st", "silk_touch", "waterworker", "aqua_affinity", "aquaaffinity", "aqua_affinity",
            "watermine", "aqua_affinity", "ww", "aqua_affinity", "firearrow", "flame", "flamearrow", "flame",
            "af", "flame", "arrowdamage", "power", "arrowpower", "power", "ad", "power",
            "arrowknockback", "punch", "arrowkb", "punch", "arrowpunch", "punch", "ak", "punch",
            "infinitearrows", "infinity", "infarrows", "infinity", "infinite", "infinity", "unlimited", "infinity",
            "unlimitedarrows", "infinity", "ai", "infinity", "luck", "luck_of_the_sea", "luckofsea", "luck_of_the_sea",
            "luckofseas", "luck_of_the_sea", "rodluck", "luck_of_the_sea", "rodlure", "lure", "depthstrider", "depth_strider",
            "depth", "depth_strider", "strider", "depth_strider", "frostwalker", "frost_walker", "frost", "frost_walker",
            "walker", "frost_walker", "bindingcurse", "binding_curse", "bindcurse", "binding_curse", "binding", "binding_curse",
            "bind", "binding_curse", "vanishingcurse", "vanishing_curse", "vanishcurse", "vanishing_curse", "vanishing", "vanishing_curse",
            "vanish", "vanishing_curse", "sweepingedge", "sweeping_edge", "sweepedge", "sweeping_edge", "sweeping", "sweeping_edge",
            "loyal", "loyalty", "return", "loyalty", "impale", "impaling", "oceandamage", "impaling",
            "oceandmg", "impaling", "rip", "riptide", "tide", "riptide", "launch", "riptide",
            "channelling", "channeling", "chanelling", "channeling", "chaneling", "channeling", "channel", "channeling",
            "tripleshot", "multishot", "quickcharge", "quick_charge", "quickdraw", "quick_charge", "fastcharge", "quick_charge",
            "fastdraw", "quick_charge", "soulspeed", "soul_speed", "soilspeed", "soul_speed", "sandspeed", "soul_speed",
            "swiftsneak", "swift_sneak", "windburst", "wind_burst", "wind", "wind_burst", "burst", "wind_burst"
    };
    private static final Map<String, String> ESSENTIALS = pairs(ESSENTIALS_ENCHANTMENTS);

    private KitItemFactory() {}

    private static Map<String, String> pairs(String[] flat) {
        Map<String, String> out = new java.util.HashMap<>();
        for (int i = 0; i + 1 < flat.length; i += 2) {
            out.put(flat[i], flat[i + 1]);
        }
        return Map.copyOf(out);
    }

    /** The vanilla key for an enchantment written in any of the supported spellings. */
    static String enchantmentKey(String raw) {
        String key = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        String alias = ALIASES.get(key);
        if (alias != null) {
            return alias;
        }
        return ESSENTIALS.getOrDefault(key, key);
    }

    /**
     * A material by Bukkit name, or by the name without underscores ({@code stonesword}) as old EssentialsX kits
     * write it when no items.json alias resolved it.
     */
    static Material material(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Material direct = Material.matchMaterial(name);
        if (direct != null) {
            return direct;
        }
        String compact = name.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").replace("_", "").replace(" ", "");
        for (Material material : Material.values()) {
            if (!material.isLegacy() && material.name().replace("_", "").equals(compact)) {
                return material;
            }
        }
        return null;
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
        Material material = material(spec.material());
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
