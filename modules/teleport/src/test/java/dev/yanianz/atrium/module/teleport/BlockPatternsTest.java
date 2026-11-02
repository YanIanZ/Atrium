package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class BlockPatternsTest {
    @Test
    void exactNamesAreCaseInsensitive() {
        assertTrue(BlockPatterns.matches(List.of("lodestone"), "LODESTONE"));
        assertTrue(BlockPatterns.matches(List.of(" LODESTONE "), "LODESTONE"));
        assertFalse(BlockPatterns.matches(List.of("LODESTONE"), "OAK_SIGN"));
    }

    @Test
    void trailingStarMatchesAPrefixWithOrWithoutTheUnderscore() {
        assertTrue(BlockPatterns.matches(List.of("*_SIGN"), "OAK_SIGN"));
        assertTrue(BlockPatterns.matches(List.of("*_SIGN"), "OAK_WALL_SIGN"));
        assertTrue(BlockPatterns.matches(List.of("*SIGN"), "OAK_SIGN"));
        assertTrue(BlockPatterns.matches(List.of("OAK_*"), "OAK_SIGN"));
        assertFalse(BlockPatterns.matches(List.of("*_SIGN"), "LODESTONE"));
    }

    @Test
    void emptyOrNullPatternsMatchNothing() {
        assertFalse(BlockPatterns.matches(List.of(), "OAK_SIGN"));
        assertFalse(BlockPatterns.matches(null, "OAK_SIGN"));
        assertFalse(BlockPatterns.matches(List.of("*"), ""));
        assertFalse(BlockPatterns.matches(List.of("  "), "OAK_SIGN"));
    }

    @Test
    void emptyWorldListMeansEveryWorld() {
        assertTrue(BlockPatterns.worldAllowed(List.of(), "world"));
        assertTrue(BlockPatterns.worldAllowed(null, "world"));
        assertTrue(BlockPatterns.worldAllowed(List.of("World_Nether"), "world_nether"));
        assertFalse(BlockPatterns.worldAllowed(List.of("world_nether"), "world"));
    }
}
