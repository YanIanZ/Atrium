package dev.yanianz.atrium.module.rtp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class BiomeFilterTest {
    private static final Set<String> NONE = Set.of();

    @Test
    void emptyListsAllowEverything() {
        assertTrue(BiomeFilter.allows(NONE, NONE, NONE, "minecraft:plains"));
        assertFalse(BiomeFilter.allows(NONE, NONE, NONE, null));
    }

    @Test
    void blockedListIsTheOnlyFilterWithoutAllowLists() {
        Set<String> blocked = Set.of("minecraft:ocean");
        assertFalse(BiomeFilter.allows(NONE, NONE, blocked, "minecraft:ocean"));
        assertTrue(BiomeFilter.allows(NONE, NONE, blocked, "minecraft:plains"));
    }

    @Test
    void allowListStillRespectsTheBlockList() {
        Set<String> allowed = Set.of("minecraft:plains", "minecraft:ocean");
        Set<String> blocked = Set.of("minecraft:ocean");
        assertTrue(BiomeFilter.allows(NONE, allowed, blocked, "minecraft:plains"));
        assertFalse(BiomeFilter.allows(NONE, allowed, blocked, "minecraft:ocean"));
        assertFalse(BiomeFilter.allows(NONE, allowed, blocked, "minecraft:desert"));
    }

    @Test
    void preferredClearsBothOtherLists() {
        Set<String> preferred = Set.of("minecraft:cherry_grove");
        Set<String> allowed = Set.of("minecraft:plains");
        Set<String> blocked = Set.of("minecraft:cherry_grove");
        assertTrue(BiomeFilter.allows(preferred, allowed, blocked, "minecraft:cherry_grove"));
        assertFalse(BiomeFilter.allows(preferred, allowed, blocked, "minecraft:plains"));
    }
}
