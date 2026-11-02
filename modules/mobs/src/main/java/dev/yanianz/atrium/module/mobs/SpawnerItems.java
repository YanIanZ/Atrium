package dev.yanianz.atrium.module.mobs;

import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

/**
 * The spawner item that keeps the mob type it was broken from. The type is written twice: as a data
 * component (so vanilla restores it when Atrium is absent) and as Atrium's own key (so a later break can
 * read it back without asking the block state).
 */
final class SpawnerItems {
    static final NamespacedKey TYPE_KEY = new NamespacedKey("atrium", "spawner_type");

    private SpawnerItems() {}

    static ItemStack item(EntityType type) {
        ItemStack stack = ItemStack.of(Material.SPAWNER);
        stack.editMeta(meta -> {
            meta.getPersistentDataContainer().set(TYPE_KEY, PersistentDataType.STRING, EntityTypes.name(type));
            meta.lore(List.of(Component.translatable(type.translationKey())
                    .color(NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));
            if (meta instanceof BlockStateMeta blockMeta) {
                BlockState state = blockMeta.getBlockState();
                if (state instanceof CreatureSpawner spawner) {
                    spawner.setSpawnedType(type);
                    blockMeta.setBlockState(spawner);
                }
            }
        });
        return stack;
    }

    /** The type carried by a spawner item, or {@code null} when it is a plain spawner. */
    static @Nullable EntityType typeOf(@Nullable ItemStack stack) {
        if (stack == null || stack.getType() != Material.SPAWNER || !stack.hasItemMeta()) {
            return null;
        }
        var meta = stack.getItemMeta();
        String stored = meta.getPersistentDataContainer().get(TYPE_KEY, PersistentDataType.STRING);
        if (stored != null) {
            return EntityTypes.parse(stored).filter(EntityTypes::spawnerType).orElse(null);
        }
        if (meta instanceof BlockStateMeta blockMeta) {
            BlockState state = blockMeta.getBlockState();
            if (state instanceof CreatureSpawner spawner) {
                EntityType type = spawner.getSpawnedType();
                return type != null && EntityTypes.spawnerType(type) ? type : null;
            }
        }
        return null;
    }

    /** The type a spawner block spawns, or {@code null} when it is not a spawner. */
    static @Nullable EntityType spawnedType(@Nullable Block block) {
        if (block == null || block.getType() != Material.SPAWNER) {
            return null;
        }
        BlockState state = block.getState();
        if (!(state instanceof CreatureSpawner spawner)) {
            return null;
        }
        EntityType type = spawner.getSpawnedType();
        return type != null && EntityTypes.spawnerType(type) ? type : null;
    }

    static boolean isSpawner(@Nullable Block block) {
        return block != null && block.getType() == Material.SPAWNER;
    }

    static boolean isPickaxe(@Nullable Material material) {
        return material != null && material.name().endsWith("_PICKAXE");
    }
}
