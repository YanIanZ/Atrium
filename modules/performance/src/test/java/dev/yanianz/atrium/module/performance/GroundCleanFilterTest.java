package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class GroundCleanFilterTest {
    private static final GroundCleanFilter FILTER = new GroundCleanFilter(
            List.of("minecraft:diamond", "nether_star", "*shulker_box", "golden_*"),
            List.of("Keep Me", "rare ?", "tag:*"));

    @Test void materialKeysAndEnumNamesBothMatch() {
        assertTrue(FILTER.keepsMaterial("minecraft:diamond", "diamond", "diamond"));
        assertTrue(FILTER.keepsMaterial("minecraft:diamond", "diamond", "diamond"));
        assertTrue(FILTER.keepsMaterial("minecraft:black_shulker_box", "black_shulker_box", "black_shulker_box"));
        assertTrue(FILTER.keepsMaterial("minecraft:golden_apple", "golden_apple", "golden_apple"));
        assertFalse(FILTER.keepsMaterial("minecraft:dirt", "dirt", "dirt"));
    }

    @Test void customNamesAreCaseInsensitiveAndGlobbed() {
        assertTrue(FILTER.keepsName("keep me"));
        assertTrue(FILTER.keepsName("  KEEP ME  "));
        assertTrue(FILTER.keepsName("rare x"));
        assertTrue(FILTER.keepsName("tag:event-prize"));
        assertFalse(FILTER.keepsName("rare xy"));
        assertFalse(FILTER.keepsName(null));
        assertFalse(FILTER.keepsName(""));
    }

    @Test void materialNamesAreNormalised() {
        assertEquals("diamond_sword", GroundCleanFilter.normalizeMaterial(" minecraft:DIAMOND SWORD "));
        assertEquals("diamond", GroundCleanFilter.normalizeMaterial("DIAMOND"));
        assertTrue(GroundCleanFilter.glob("a.b").matcher("A.B").matches());
        assertFalse(GroundCleanFilter.glob("a.b").matcher("axb").matches(), "dots stay literal");
    }

    @Test void emptyWhitelistKeepsNothing() {
        GroundCleanFilter empty = new GroundCleanFilter(List.of(), List.of());
        assertFalse(empty.keepsMaterial("minecraft:diamond", "diamond", "diamond"));
        assertFalse(empty.keepsName("anything"));
    }
}
