package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArmorEffectOwnershipTest {
    @Test
    void encodeSortsDeduplicatesAndIgnoresBlanks() {
        assertEquals("minecraft:haste,minecraft:speed",
                ArmorEffectOwnership.encode(List.of("minecraft:speed", " minecraft:haste ", "minecraft:speed")));
        assertEquals("", ArmorEffectOwnership.encode(List.of()));
        assertEquals("", ArmorEffectOwnership.encode(null));
        assertEquals("minecraft:speed", ArmorEffectOwnership.encode(List.of(" ", "minecraft:speed")));
    }

    @Test
    void decodeHandlesEmptyAndMalformedValues() {
        assertEquals(List.of("minecraft:speed", "minecraft:haste"),
                ArmorEffectOwnership.decode("minecraft:speed, ,minecraft:haste,minecraft:haste"));
        assertTrue(ArmorEffectOwnership.decode(null).isEmpty());
        assertTrue(ArmorEffectOwnership.decode("  ").isEmpty());
        assertTrue(ArmorEffectOwnership.decode(",").isEmpty());
    }

    @Test
    void roundTripKeepsEveryKey() {
        List<String> keys = List.of("minecraft:resistance", "minecraft:water_breathing");
        assertEquals(keys, ArmorEffectOwnership.decode(ArmorEffectOwnership.encode(keys)));
    }
}
