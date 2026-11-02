package dev.yanianz.atrium.module.worth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class PriceMaterialsTest {
    private final PriceMaterials materials = PriceMaterials.of(
            List.of("diamond", "diamond_pickaxe", "cobblestone", "white_wool"));

    @Test
    void modernNamesResolveCaseInsensitively() {
        assertEquals("diamond", materials.resolve("DIAMOND", false).orElseThrow());
        assertEquals("cobblestone", materials.resolve(" Cobblestone ", false).orElseThrow());
    }

    @Test
    void unknownAndNamespacedNamesAreRefused() {
        assertTrue(materials.resolve("unobtainium", false).isEmpty());
        assertTrue(materials.resolve("ia:custom_sword", false).isEmpty());
        assertTrue(materials.resolve("wool", false).isEmpty());
        assertTrue(materials.resolve("", false).isEmpty());
    }

    @Test
    void legacyNamesResolveOnlyWhenTheSourceAllowsThem() {
        assertEquals("diamond_pickaxe", materials.resolve("diamondpickaxe", true).orElseThrow());
        assertTrue(materials.resolve("diamondpickaxe", false).isEmpty());
        // `wool` only matches when one wool material exists; here the name is split across four entries.
        assertTrue(materials.resolve("wool", true).isEmpty());
    }

    @Test
    void theItemPredicateUsesTheSnapshot() {
        assertTrue(materials.isItem("DIAMOND"));
        assertFalse(materials.isItem("water"));
        assertFalse(materials.isItem(null));
    }
}
