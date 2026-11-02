package dev.yanianz.atrium.module.worldrules;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Material;

/**
 * Pure helpers for the gameplay flags: spawn categories, trampling and the tri-state world limits.
 * No server state is touched, so every rule here is unit-testable.
 */
final class WorldFlags {
    private WorldFlags() {}

    /** Broad mob groups a world can block by name. */
    enum SpawnCategory {
        MONSTER,
        ANIMAL,
        WATER,
        AMBIENT,
        OTHER;

        static SpawnCategory parse(String value) {
            if (value == null) {
                return null;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "monster", "monsters", "hostile" -> MONSTER;
                case "animal", "animals", "passive" -> ANIMAL;
                case "water", "water_ambient", "water-ambient" -> WATER;
                case "ambient", "ambients" -> AMBIENT;
                case "other", "misc" -> OTHER;
                default -> null;
            };
        }
    }

    /**
     * Parses {@code block-mob-spawns}. Unknown names are reported through {@code warn} and skipped;
     * {@code ALL} expands to every category.
     */
    static Set<SpawnCategory> spawnCategories(List<String> values, Consumer<String> warn) {
        EnumSet<SpawnCategory> categories = EnumSet.noneOf(SpawnCategory.class);
        if (values == null) {
            return categories;
        }
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (value.trim().equalsIgnoreCase("all")) {
                categories.addAll(EnumSet.allOf(SpawnCategory.class));
                continue;
            }
            SpawnCategory category = SpawnCategory.parse(value);
            if (category == null) {
                warn.accept("unknown mob category '" + value + "' (use MONSTER, ANIMAL, WATER, AMBIENT, OTHER or ALL)");
            } else {
                categories.add(category);
            }
        }
        return categories;
    }

    /** Whether one spawn attempt is denied by a world rule. */
    static boolean blocksSpawn(Set<SpawnCategory> blocked, SpawnCategory category, boolean spawnerReason,
            boolean blockSpawnerMobs) {
        if (blocked == null || blocked.isEmpty()) {
            return false;
        }
        if (spawnerReason && blockSpawnerMobs) {
            return true;
        }
        return category != null && blocked.contains(category);
    }

    /** Farmland is the only block a player tramples into dirt by walking on it. */
    static boolean tramples(Material type) {
        return type == Material.FARMLAND;
    }
}
