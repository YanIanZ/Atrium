package dev.yanianz.atrium.module.mobs;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** Spawn eggs mapped back to the entity they create, built from the material registry once. */
final class SpawnEggs {
    private static final Map<Material, EntityType> BY_MATERIAL = build();

    private SpawnEggs() {}

    private static Map<Material, EntityType> build() {
        Map<Material, EntityType> map = new EnumMap<>(Material.class);
        for (EntityType type : EntityTypes.spawnableTypes()) {
            String name = EntityTypes.name(type).toUpperCase(Locale.ROOT) + "_SPAWN_EGG";
            Material material = Material.matchMaterial(name);
            if (material != null) {
                map.put(material, type);
            }
        }
        return Map.copyOf(map);
    }

    static @Nullable EntityType of(@Nullable ItemStack stack) {
        return stack == null ? null : BY_MATERIAL.get(stack.getType());
    }

    /** Entity types that have an egg; the per-type permission nodes are registered for these only. */
    static Set<EntityType> types() {
        return Set.copyOf(BY_MATERIAL.values());
    }
}
