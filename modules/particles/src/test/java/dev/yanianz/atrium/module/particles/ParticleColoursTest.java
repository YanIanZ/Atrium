package dev.yanianz.atrium.module.particles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;

class ParticleColoursTest {
    private static final Color RED = Color.fromRGB(0xFF0000);
    private static final Color BLUE = Color.fromRGB(0x0000FF);

    @Test
    void fixedColourNeverChanges() {
        ParticleColours colours = new ParticleColours(ParticleColours.Mode.FIXED, RED, BLUE);
        assertEquals(RED, colours.at(0, 10, 0));
        assertEquals(RED, colours.at(9, 10, 500));
    }

    @Test
    void gradientUsesBothEndsAndBlendsInside() {
        ParticleColours colours = new ParticleColours(ParticleColours.Mode.GRADIENT, RED, BLUE);
        assertEquals(RED, colours.at(0, 3, 0));
        assertEquals(BLUE, colours.at(2, 3, 0));
        Color middle = colours.at(1, 3, 0);
        assertEquals(128, middle.getRed(), 1);
        assertEquals(0, middle.getGreen());
        assertEquals(127, middle.getBlue(), 1);
    }

    @Test
    void rainbowCyclesWithTicksAndPoints() {
        ParticleColours colours = new ParticleColours(ParticleColours.Mode.RAINBOW, RED, BLUE);
        Color first = colours.at(0, 12, 0);
        Color later = colours.at(0, 12, 15);
        Color otherPoint = colours.at(6, 12, 0);
        assertNotEquals(first, later);
        assertNotEquals(first, otherPoint);
    }

    @Test
    void modeNamesAreCaseInsensitive() {
        assertEquals(ParticleColours.Mode.RAINBOW, ParticleColours.Mode.byName("Rainbow"));
        assertEquals(ParticleColours.Mode.GRADIENT, ParticleColours.Mode.byName("gradient"));
        assertNull(ParticleColours.Mode.byName("unknown"));
        assertNull(ParticleColours.Mode.byName(null));
    }

    @Test
    void parsesHexAndNamedColoursWithFallback() {
        assertEquals(Color.fromRGB(0x5FB3A1), ParticleColours.parse("#5FB3A1", RED));
        assertEquals(Color.fromRGB(0xFFFFFF), ParticleColours.parse("white", RED));
        assertEquals(RED, ParticleColours.parse("not-a-colour", RED));
        assertEquals("#5FB3A1", ParticleColours.hex(Color.fromRGB(0x5FB3A1)));
    }
}
