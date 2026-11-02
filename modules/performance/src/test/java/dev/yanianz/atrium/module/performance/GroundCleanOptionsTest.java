package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class GroundCleanOptionsTest {
    @Test void emptyMeansEveryWorldWithDefaults() {
        GroundCleanOptions options = GroundCleanOptions.parse("");
        assertEquals("", options.world());
        assertFalse(options.silent());
        assertFalse(options.immediate());
        assertEquals(options, GroundCleanOptions.parse(null));
    }

    @Test void flagsAndWorldAreIndependent() {
        GroundCleanOptions options = GroundCleanOptions.parse("world_nether -s -now");
        assertEquals("world_nether", options.world());
        assertTrue(options.silent());
        assertTrue(options.immediate());

        GroundCleanOptions explicit = GroundCleanOptions.parse("world:my_world --silent --force");
        assertEquals("my_world", explicit.world());
        assertTrue(explicit.silent());
        assertTrue(explicit.immediate());
    }

    @Test void worldsWithSpacesAreJoined() {
        GroundCleanOptions options = GroundCleanOptions.parse("My World -s");
        assertEquals("My World", options.world());
        assertTrue(options.silent());
    }

    @Test void unknownFlagsAndEmptyWorldPrefixAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> GroundCleanOptions.parse("-x"));
        assertThrows(IllegalArgumentException.class, () -> GroundCleanOptions.parse("world:"));
    }
}
