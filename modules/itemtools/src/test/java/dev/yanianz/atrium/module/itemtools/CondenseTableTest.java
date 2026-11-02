package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class CondenseTableTest {
    @Test
    void planNeverConsumesMoreThanAvailableAndNeverCreatesItems() {
        for (int ratio : new int[] {4, 9}) {
            for (int available = 0; available <= 500; available++) {
                CondenseTable.Plan plan = CondenseTable.plan(available, ratio);
                assertTrue(plan.consumed() <= available, "consumed more than available");
                assertEquals(0, plan.consumed() % ratio, "consumed must be a complete group");
                assertEquals(plan.consumed(), plan.produced() * ratio, "output must be exactly the input cost");
            }
        }
    }

    @Test
    void planDropsLeftoversAndKeepsCompleteGroups() {
        assertEquals(new CondenseTable.Plan(0, 0), CondenseTable.plan(8, 9));
        assertEquals(new CondenseTable.Plan(9, 1), CondenseTable.plan(9, 9));
        assertEquals(new CondenseTable.Plan(18, 2), CondenseTable.plan(26, 9));
        assertEquals(new CondenseTable.Plan(0, 0), CondenseTable.plan(3, 4));
        assertEquals(new CondenseTable.Plan(4, 1), CondenseTable.plan(7, 4));
        assertEquals(new CondenseTable.Plan(8, 2), CondenseTable.plan(8, 4));
        assertEquals(new CondenseTable.Plan(0, 0), CondenseTable.plan(0, 9));
        assertEquals(new CondenseTable.Plan(0, 0), CondenseTable.plan(-5, 9));
        assertEquals(new CondenseTable.Plan(0, 0), CondenseTable.plan(10, 7));
    }

    @Test
    void onlyExactFourToOneAndNineToOneRecipesAreWhitelisted() {
        Set<Material> inputs = new HashSet<>();
        for (CondenseTable.Recipe recipe : CondenseTable.recipes()) {
            assertTrue(recipe.ratio() == 4 || recipe.ratio() == 9, recipe.from() + " has an invalid ratio");
            assertTrue(inputs.add(recipe.from()), "duplicate input " + recipe.from());
            assertTrue(CondenseTable.recipeFor(recipe.from()).isPresent());
            assertEquals(recipe, CondenseTable.recipeFor(recipe.from()).orElseThrow());
        }
        assertFalse(CondenseTable.recipeFor(Material.DIRT).isPresent());
        assertFalse(CondenseTable.recipeFor(Material.DIAMOND_BLOCK).isPresent(), "blocks must not condense backwards");
    }

    @Test
    void knownExamplesUseTheCorrectVanillaRecipe() {
        assertEquals(new CondenseTable.Recipe(Material.IRON_NUGGET, Material.IRON_INGOT, 9),
                CondenseTable.recipeFor(Material.IRON_NUGGET).orElseThrow());
        assertEquals(new CondenseTable.Recipe(Material.IRON_INGOT, Material.IRON_BLOCK, 9),
                CondenseTable.recipeFor(Material.IRON_INGOT).orElseThrow());
        assertEquals(new CondenseTable.Recipe(Material.AMETHYST_SHARD, Material.AMETHYST_BLOCK, 4),
                CondenseTable.recipeFor(Material.AMETHYST_SHARD).orElseThrow());
        assertEquals(new CondenseTable.Recipe(Material.QUARTZ, Material.QUARTZ_BLOCK, 4),
                CondenseTable.recipeFor(Material.QUARTZ).orElseThrow());
    }

    @Test
    void planRejectsInvalidConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new CondenseTable.Recipe(Material.STONE, Material.STONE, 9));
        assertThrows(IllegalArgumentException.class, () -> new CondenseTable.Recipe(Material.STONE, Material.DIRT, 5));
        assertThrows(IllegalArgumentException.class, () -> new CondenseTable.Plan(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new CondenseTable.Plan(9, 0));
    }
}
