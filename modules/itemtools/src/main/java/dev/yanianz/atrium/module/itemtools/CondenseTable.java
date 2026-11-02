package dev.yanianz.atrium.module.itemtools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bukkit.Material;

/**
 * The fixed condense whitelist. Only exact 9:1 or 4:1 crafting recipes are listed; no recipe is ever
 * discovered from the server, so a data pack or another plugin cannot make this command create items.
 *
 * <p>The arithmetic is pure and unit tested. {@link #plan(int, int)} always returns a consumed amount
 * that is a multiple of the ratio and a produced amount that cannot exceed what the consumed items make.
 */
final class CondenseTable {
    record Recipe(Material from, Material to, int ratio) {
        Recipe {
            if (from == null || to == null || from == to) {
                throw new IllegalArgumentException("Condense recipe needs two different materials");
            }
            if (ratio != 4 && ratio != 9) {
                throw new IllegalArgumentException("Condense ratio must be 4 or 9");
            }
        }
    }

    /** Amount of input items actually used and the resulting output item count. */
    record Plan(int consumed, int produced) {
        Plan {
            if (consumed < 0 || produced < 0 || (consumed == 0) != (produced == 0)) {
                throw new IllegalArgumentException("Invalid condense plan");
            }
        }
    }

    private static final List<Recipe> RECIPES = List.of(
            // Nuggets to ingots (9:1). Kept first so one /condense can also turn the result into blocks.
            new Recipe(Material.IRON_NUGGET, Material.IRON_INGOT, 9),
            new Recipe(Material.GOLD_NUGGET, Material.GOLD_INGOT, 9),
            new Recipe(Material.COPPER_NUGGET, Material.COPPER_INGOT, 9),
            // Ingots and loose materials to blocks (9:1).
            new Recipe(Material.IRON_INGOT, Material.IRON_BLOCK, 9),
            new Recipe(Material.GOLD_INGOT, Material.GOLD_BLOCK, 9),
            new Recipe(Material.COPPER_INGOT, Material.COPPER_BLOCK, 9),
            new Recipe(Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK, 9),
            new Recipe(Material.DIAMOND, Material.DIAMOND_BLOCK, 9),
            new Recipe(Material.EMERALD, Material.EMERALD_BLOCK, 9),
            new Recipe(Material.LAPIS_LAZULI, Material.LAPIS_BLOCK, 9),
            new Recipe(Material.REDSTONE, Material.REDSTONE_BLOCK, 9),
            new Recipe(Material.COAL, Material.COAL_BLOCK, 9),
            new Recipe(Material.RAW_IRON, Material.RAW_IRON_BLOCK, 9),
            new Recipe(Material.RAW_GOLD, Material.RAW_GOLD_BLOCK, 9),
            new Recipe(Material.RAW_COPPER, Material.RAW_COPPER_BLOCK, 9),
            new Recipe(Material.SLIME_BALL, Material.SLIME_BLOCK, 9),
            new Recipe(Material.BONE_MEAL, Material.BONE_BLOCK, 9),
            new Recipe(Material.WHEAT, Material.HAY_BLOCK, 9),
            new Recipe(Material.DRIED_KELP, Material.DRIED_KELP_BLOCK, 9),
            new Recipe(Material.BAMBOO, Material.BAMBOO_BLOCK, 9),
            new Recipe(Material.NETHER_WART, Material.NETHER_WART_BLOCK, 9),
            // 2x2 recipes (4:1).
            new Recipe(Material.AMETHYST_SHARD, Material.AMETHYST_BLOCK, 4),
            new Recipe(Material.GLOWSTONE_DUST, Material.GLOWSTONE, 4),
            new Recipe(Material.MAGMA_CREAM, Material.MAGMA_BLOCK, 4),
            new Recipe(Material.SNOWBALL, Material.SNOW_BLOCK, 4),
            new Recipe(Material.CLAY_BALL, Material.CLAY, 4),
            new Recipe(Material.NETHER_BRICK, Material.NETHER_BRICKS, 4),
            new Recipe(Material.PRISMARINE_SHARD, Material.PRISMARINE, 4),
            new Recipe(Material.POPPED_CHORUS_FRUIT, Material.PURPUR_BLOCK, 4),
            new Recipe(Material.SAND, Material.SANDSTONE, 4),
            new Recipe(Material.RED_SAND, Material.RED_SANDSTONE, 4),
            new Recipe(Material.BRICK, Material.BRICKS, 4),
            new Recipe(Material.HONEYCOMB, Material.HONEYCOMB_BLOCK, 4),
            new Recipe(Material.QUARTZ, Material.QUARTZ_BLOCK, 4));

    private static final Map<Material, Recipe> BY_INPUT = indexInput();
    private static final Map<Material, Recipe> BY_OUTPUT = indexOutput();

    private CondenseTable() {
    }

    private static Map<Material, Recipe> indexInput() {
        Map<Material, Recipe> index = new LinkedHashMap<>();
        for (Recipe recipe : RECIPES) {
            Recipe previous = index.put(recipe.from(), recipe);
            if (previous != null) {
                throw new IllegalStateException("Duplicate condense input: " + recipe.from());
            }
        }
        return Map.copyOf(index);
    }

    private static Map<Material, Recipe> indexOutput() {
        Map<Material, Recipe> index = new LinkedHashMap<>();
        for (Recipe recipe : RECIPES) {
            Recipe previous = index.put(recipe.to(), recipe);
            if (previous != null) {
                throw new IllegalStateException("Duplicate condense output: " + recipe.to());
            }
        }
        return Map.copyOf(index);
    }

    static List<Recipe> recipes() {
        return RECIPES;
    }

    static Optional<Recipe> recipeFor(Material input) {
        return Optional.ofNullable(BY_INPUT.get(input));
    }

    static List<Material> inputs() {
        return new ArrayList<>(BY_INPUT.keySet());
    }

    /** Uses only complete groups; leftover input stays in the inventory. */
    static Plan plan(int available, int ratio) {
        if (available <= 0 || (ratio != 4 && ratio != 9)) {
            return new Plan(0, 0);
        }
        int consumed = (available / ratio) * ratio;
        return new Plan(consumed, consumed / ratio);
    }

    /** The recipe that turns a block back into the loose material (/uncondense). */
    static Optional<Recipe> reverseRecipeFor(Material output) {
        return Optional.ofNullable(BY_OUTPUT.get(output));
    }

    /** Every material /uncondense accepts, in whitelist order. */
    static List<Material> outputs() {
        return new ArrayList<>(BY_OUTPUT.keySet());
    }

    /**
     * Splits whole blocks back into loose materials. Every single block is a complete group, so nothing is
     * ever left over: {@code available} blocks become {@code available * ratio} items. Rejects an available
     * amount that would overflow and never reports negative output.
     */
    static Plan reversePlan(int available, int ratio) {
        if (available <= 0 || (ratio != 4 && ratio != 9)) {
            return new Plan(0, 0);
        }
        long produced = (long) available * ratio;
        if (produced > Integer.MAX_VALUE) {
            return new Plan(0, 0);
        }
        return new Plan(available, (int) produced);
    }
}
