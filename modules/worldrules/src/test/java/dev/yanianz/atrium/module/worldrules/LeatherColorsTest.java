package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

class LeatherColorsTest {
    @Test
    void hueWrapsInBothDirections() {
        assertEquals(0, LeatherColors.wrapHue(360));
        assertEquals(10, LeatherColors.nextHue(355, 15));
        assertEquals(350, LeatherColors.wrapHue(-10));
    }

    @Test
    void primaryHuesProduceTheirFullChannels() {
        assertEquals(0xFF0000, LeatherColors.rgb(0, 100, 100));
        assertEquals(0x00FF00, LeatherColors.rgb(120, 100, 100));
        assertEquals(0x0000FF, LeatherColors.rgb(240, 100, 100));
        assertEquals(0xFFFFFF, LeatherColors.rgb(45, 0, 100));
        assertEquals(0x000000, LeatherColors.rgb(200, 100, 0));
    }

    @Test
    void onlyLeatherPiecesAreAnimated() {
        assertTrue(LeatherColors.leather(Material.LEATHER_HELMET));
        assertTrue(LeatherColors.leather(Material.LEATHER_BOOTS));
        assertFalse(LeatherColors.leather(Material.IRON_CHESTPLATE));
        assertFalse(LeatherColors.leather(null));
    }
}
