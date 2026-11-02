package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class SkullPreservationTest {
    @Test
    void wallSkullsMapToTheirStandingVariant() {
        assertEquals(Material.PLAYER_HEAD, SkullPreservation.standing(Material.PLAYER_WALL_HEAD));
        assertEquals(Material.SKELETON_SKULL, SkullPreservation.standing(Material.SKELETON_WALL_SKULL));
        assertEquals(Material.WITHER_SKELETON_SKULL,
                SkullPreservation.standing(Material.WITHER_SKELETON_WALL_SKULL));
        assertEquals(Material.ZOMBIE_HEAD, SkullPreservation.standing(Material.ZOMBIE_WALL_HEAD));
        assertEquals(Material.DRAGON_HEAD, SkullPreservation.standing(Material.DRAGON_HEAD));
        assertEquals(Material.STONE, SkullPreservation.standing(Material.STONE));
    }

    @Test
    void everyWallVariantHasAStandingVariantThatIsASkull() {
        for (Material material : Material.values()) {
            String name = material.name();
            if (!name.contains("_WALL_") || !SkullPreservation.isSkull(material)) {
                continue;
            }
            Material standing = SkullPreservation.standing(material);
            assertFalse(standing == material, material + " was not mapped");
            assertTrue(SkullPreservation.isSkull(standing), standing + " is not a skull");
        }
    }

    @Test
    void onlyHeadLikeMaterialsCountAsSkulls() {
        assertTrue(SkullPreservation.isSkull(Material.PLAYER_HEAD));
        assertTrue(SkullPreservation.isSkull(Material.SKELETON_SKULL));
        assertTrue(SkullPreservation.isSkull(Material.PLAYER_WALL_HEAD));
        assertFalse(SkullPreservation.isSkull(Material.STONE));
        assertFalse(SkullPreservation.isSkull(Material.PUMPKIN));
        assertFalse(SkullPreservation.isSkull(Material.AIR));
    }
}
