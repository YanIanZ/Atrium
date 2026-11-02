package dev.yanianz.atrium.module.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ColorPickerTest {
    @Test
    void namedPaletteHasSixteenStandardColoursWithTags() {
        assertEquals(16, ColorPicker.NAMED.size());
        assertEquals("<red>", ColorPicker.NAMED.stream()
                .filter(swatch -> swatch.label().equals("red"))
                .findFirst().orElseThrow().tag());
        assertEquals(0xFF5555, ColorPicker.NAMED.stream()
                .filter(swatch -> swatch.label().equals("red"))
                .findFirst().orElseThrow().rgb());
    }

    @Test
    void hexGridHasTwentyFourDistinctValidSwatches() {
        var grid = ColorPicker.hexGrid();
        assertEquals(24, grid.size());
        Set<Integer> rgb = new HashSet<>();
        for (ColorPicker.Swatch swatch : grid) {
            assertTrue(swatch.hex().matches("#[0-9a-f]{6}"), swatch.hex());
            assertEquals("<" + swatch.hex() + ">", swatch.tag());
            assertTrue(rgb.add(swatch.rgb()), "duplicate swatch " + swatch.hex());
        }
        assertNotEquals(grid.getFirst().rgb(), grid.get(12).rgb());
    }

    @Test
    void hsvConversionHandlesThePrimaryHues() {
        assertEquals(0xFF0000, ColorPicker.hsv(0, 1, 1));
        assertEquals(0x00FF00, ColorPicker.hsv(120, 1, 1));
        assertEquals(0x0000FF, ColorPicker.hsv(240, 1, 1));
        assertEquals(0xFFFFFF, ColorPicker.hsv(77, 0, 1));
    }
}
