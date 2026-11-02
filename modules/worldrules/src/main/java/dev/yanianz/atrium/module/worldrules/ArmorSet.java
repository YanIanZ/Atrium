package dev.yanianz.atrium.module.worldrules;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Material;
import org.bukkit.potion.PotionEffectType;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** One configured armor set ({@code armor-sets.<name>} in worldrules.yml). */
@ConfigSerializable
public class ArmorSet {
    @Comment("How many of the configured pieces must match at once (1-4, clamped to the number of pieces).")
    public int requiredPieces = 4;

    @Comment("Pieces by slot: helmet, chestplate, leggings, boots. A missing slot is not required.")
    public Map<String, ArmorPiece> pieces = new LinkedHashMap<>();

    @Comment("Infinite potion effects granted while the set is complete. The first matching set wins.")
    public List<EffectSpec> effects = new ArrayList<>();

    @ConfigSerializable
    public static class ArmorPiece {
        @Comment("Accepted materials in this slot. Empty accepts any non-air item.")
        public List<String> materials = new ArrayList<>();

        @Comment("Required custom model data (0 = any). An item with a different custom model data does not match.")
        public int customModelData = 0;
    }

    @ConfigSerializable
    public static class EffectSpec {
        @Comment("Potion effect name, for example SPEED, NIGHT_VISION or WATER_BREATHING.")
        public String type = "SPEED";

        @Comment("Amplifier, 0 = level I (0-255).")
        public int amplifier = 0;
    }

    /** Armor slots indexed exactly like {@code PlayerInventory#getArmorContents()}. */
    enum Slot {
        BOOTS,
        LEGGINGS,
        CHESTPLATE,
        HELMET;

        static Slot parse(String key) {
            return switch (key.trim().toLowerCase(Locale.ROOT)) {
                case "boots", "feet", "boot" -> BOOTS;
                case "leggings", "legs", "leg" -> LEGGINGS;
                case "chestplate", "chest" -> CHESTPLATE;
                case "helmet", "head" -> HELMET;
                default -> null;
            };
        }
    }

    record CompiledEffect(PotionEffectType type, int amplifier) {}

    record CompiledPiece(Set<Material> materials, int customModelData) {
        boolean matches(Material material, int modelData) {
            if (material == null || material == Material.AIR) {
                return false;
            }
            if (!materials.isEmpty() && !materials.contains(material)) {
                return false;
            }
            return customModelData <= 0 || modelData == customModelData;
        }
    }

    static final class CompiledSet {
        private final String name;
        private final int required;
        private final Map<Slot, CompiledPiece> pieces;
        private final List<CompiledEffect> effects;

        CompiledSet(String name, int required, Map<Slot, CompiledPiece> pieces, List<CompiledEffect> effects) {
            this.name = name;
            this.required = required;
            this.pieces = Map.copyOf(pieces);
            this.effects = List.copyOf(effects);
        }

        String name() {
            return name;
        }

        List<CompiledEffect> effects() {
            return effects;
        }

        /**
         * Arrays are indexed by {@link Slot#ordinal()} (boots, leggings, chestplate, helmet), like
         * {@code PlayerInventory#getArmorContents()}. Missing slots are simply not required.
         */
        boolean matches(Material[] materials, int[] customModelData) {
            int matched = 0;
            for (Map.Entry<Slot, CompiledPiece> entry : pieces.entrySet()) {
                int index = entry.getKey().ordinal();
                Material material = index < materials.length ? materials[index] : null;
                int modelData = index < customModelData.length ? customModelData[index] : -1;
                if (entry.getValue().matches(material, modelData)) {
                    matched++;
                }
            }
            return matched >= required;
        }
    }

    /** Builds a validated set, warning about broken entries instead of failing the module. */
    CompiledSet compile(String name, Consumer<String> warn) {
        if (pieces == null || pieces.isEmpty()) {
            warn.accept("armor set '" + name + "' has no pieces");
            return null;
        }
        if (effects == null || effects.isEmpty()) {
            warn.accept("armor set '" + name + "' has no effects");
            return null;
        }
        Map<Slot, CompiledPiece> compiledPieces = new EnumMap<>(Slot.class);
        for (Map.Entry<String, ArmorPiece> entry : pieces.entrySet()) {
            Slot slot = Slot.parse(entry.getKey());
            if (slot == null) {
                warn.accept("armor set '" + name + "': unknown slot '" + entry.getKey() + "'");
                continue;
            }
            ArmorPiece piece = entry.getValue();
            if (piece == null) {
                continue;
            }
            Set<Material> materials = new java.util.HashSet<>();
            if (piece.materials != null) {
                for (String text : piece.materials) {
                    if (text == null || text.isBlank()) {
                        continue;
                    }
                    Material material = Material.matchMaterial(text.trim());
                    if (material == null || material.isAir() || !material.isItem()) {
                        warn.accept("armor set '" + name + "' slot '" + entry.getKey() + "': unknown material '" + text + "'");
                        continue;
                    }
                    materials.add(material);
                }
            }
            if (piece.materials != null && !piece.materials.isEmpty() && materials.isEmpty()) {
                warn.accept("armor set '" + name + "' slot '" + entry.getKey() + "' has no valid material");
                continue;
            }
            int modelData = Math.max(0, piece.customModelData);
            compiledPieces.put(slot, new CompiledPiece(Set.copyOf(materials), modelData));
        }
        if (compiledPieces.isEmpty()) {
            warn.accept("armor set '" + name + "' has no valid pieces");
            return null;
        }

        Map<PotionEffectType, Integer> byType = new HashMap<>();
        for (EffectSpec spec : effects) {
            if (spec == null || spec.type == null || spec.type.isBlank()) {
                continue;
            }
            PotionEffectType type = effect(spec.type);
            if (type == null) {
                warn.accept("armor set '" + name + "': unknown potion effect '" + spec.type + "'");
                continue;
            }
            int amplifier = Math.max(0, Math.min(255, spec.amplifier));
            byType.merge(type, amplifier, Math::max);
        }
        if (byType.isEmpty()) {
            warn.accept("armor set '" + name + "' has no valid effects");
            return null;
        }
        List<CompiledEffect> compiledEffects = new ArrayList<>();
        byType.forEach((type, amplifier) -> compiledEffects.add(new CompiledEffect(type, amplifier)));
        int required = Math.max(1, Math.min(4, Math.min(requiredPieces, compiledPieces.size())));
        return new CompiledSet(name, required, compiledPieces, compiledEffects);
    }

    private static PotionEffectType effect(String name) {
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            PotionEffectType type = org.bukkit.Registry.POTION_EFFECT_TYPE.get(
                    org.bukkit.NamespacedKey.fromString(normalized.toLowerCase(Locale.ROOT)));
            if (type != null) {
                return type;
            }
        } catch (IllegalArgumentException ignored) {
            // not a namespaced key; try the legacy spelling below
        }
        return legacyEffect(normalized);
    }

    @SuppressWarnings("deprecation")
    private static PotionEffectType legacyEffect(String normalized) {
        return PotionEffectType.getByName(normalized);
    }
}
