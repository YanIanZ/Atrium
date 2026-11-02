package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class WorldFlagsTest {
    @Test
    void allExpandsToEveryCategory() {
        Set<WorldFlags.SpawnCategory> categories =
                WorldFlags.spawnCategories(List.of("ALL"), message -> { });
        assertEquals(Set.of(WorldFlags.SpawnCategory.values()), categories);
    }

    @Test
    void namesAcceptAliasesAndUnknownNamesAreReported() {
        List<String> warnings = new ArrayList<>();
        Set<WorldFlags.SpawnCategory> categories = WorldFlags.spawnCategories(
                List.of("hostile", "water-ambient", "nope", " "), warnings::add);
        assertEquals(Set.of(WorldFlags.SpawnCategory.MONSTER, WorldFlags.SpawnCategory.WATER), categories);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("nope"));
    }

    @Test
    void spawnerReasonOnlyBlocksWhenEnabled() {
        Set<WorldFlags.SpawnCategory> monsters = Set.of(WorldFlags.SpawnCategory.MONSTER);
        assertTrue(WorldFlags.blocksSpawn(monsters, WorldFlags.SpawnCategory.MONSTER, false, false));
        assertFalse(WorldFlags.blocksSpawn(monsters, WorldFlags.SpawnCategory.ANIMAL, false, false));
        assertTrue(WorldFlags.blocksSpawn(monsters, WorldFlags.SpawnCategory.ANIMAL, true, true));
        assertFalse(WorldFlags.blocksSpawn(monsters, WorldFlags.SpawnCategory.ANIMAL, true, false));
        assertFalse(WorldFlags.blocksSpawn(Set.of(), WorldFlags.SpawnCategory.MONSTER, true, true));
    }

    @Test
    void onlyFarmlandCanBeTrampled() {
        assertTrue(WorldFlags.tramples(Material.FARMLAND));
        assertFalse(WorldFlags.tramples(Material.DIRT));
        assertFalse(WorldFlags.tramples(null));
    }
}
