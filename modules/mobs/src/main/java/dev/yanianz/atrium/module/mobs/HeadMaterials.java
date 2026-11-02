package dev.yanianz.atrium.module.mobs;

import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.Nullable;

/** Entity types that have a vanilla head or skull item. */
final class HeadMaterials {
    private HeadMaterials() {}

    static @Nullable Material of(EntityType type) {
        return switch (type) {
            case SKELETON, STRAY, BOGGED -> Material.SKELETON_SKULL;
            case WITHER_SKELETON -> Material.WITHER_SKELETON_SKULL;
            case ZOMBIE, HUSK, DROWNED, ZOMBIE_VILLAGER -> Material.ZOMBIE_HEAD;
            case CREEPER -> Material.CREEPER_HEAD;
            case PIGLIN, PIGLIN_BRUTE, ZOMBIFIED_PIGLIN -> Material.PIGLIN_HEAD;
            case ENDER_DRAGON -> Material.DRAGON_HEAD;
            case PLAYER -> Material.PLAYER_HEAD;
            default -> null;
        };
    }
}
