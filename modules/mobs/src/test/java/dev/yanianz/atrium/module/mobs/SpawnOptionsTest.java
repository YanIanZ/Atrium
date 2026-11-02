package dev.yanianz.atrium.module.mobs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SpawnOptionsTest {
    @Test void emptyTextIsNoOptions() {
        assertEquals(SpawnOptions.NONE, SpawnOptions.parse(null, 100));
        assertEquals(SpawnOptions.NONE, SpawnOptions.parse("   ", 100));
        assertTrue(SpawnOptions.NONE.isEmpty());
    }

    @Test void parsesValuesInAllSeparatorStyles() {
        SpawnOptions options = SpawnOptions.parse(
                "t:30 hp-40 name{Bob Smith} speed=2 scale{1.5} pickup:false", 600);
        assertEquals(30, options.lifetimeSeconds());
        assertEquals(40, options.health());
        assertEquals("Bob Smith", options.name());
        assertEquals(2, options.speed());
        assertEquals(1.5, options.scale());
        assertEquals(Boolean.FALSE, options.pickup());
    }

    @Test void clampsToSafeRanges() {
        SpawnOptions options = SpawnOptions.parse("t:99999 hp:99999 speed:99 scale:99", 600);
        assertEquals(600, options.lifetimeSeconds());
        assertEquals(2048, options.health());
        assertEquals(5, options.speed());
        assertEquals(10, options.scale());
        assertEquals(1, SpawnOptions.parse("hp:-5 t:0", 600).health());
        assertEquals(0.05, SpawnOptions.parse("t:0", 600).lifetimeSeconds());
    }

    @Test void booleanFlagsToggleBothWays() {
        SpawnOptions on = SpawnOptions.parse("glow nogravity baby noai silent charged jeb", 60);
        assertTrue(on.glow());
        assertTrue(on.noGravity());
        assertTrue(on.noAi());
        assertEquals(Boolean.TRUE, on.baby());
        assertTrue(on.silent());
        assertTrue(on.charged());
        assertTrue(on.jeb());

        SpawnOptions off = SpawnOptions.parse("glow:false -nogravity gravity ai adult", 60);
        assertFalse(off.glow());
        assertFalse(off.noGravity());
        assertFalse(off.noAi());
        assertEquals(Boolean.FALSE, off.baby());
    }

    @Test void keepsBraceGroupsTogether() {
        assertEquals(java.util.List.of("name{Two Words}", "hp:3"), SpawnOptions.split("name{Two Words}   hp:3"));
    }

    @Test void equipmentSlotsAreRecognised() {
        SpawnOptions options = SpawnOptions.parse("head:iron_helmet chestplate:diamond_chestplate mainhand:stick", 60);
        assertEquals(Map.of(SpawnOptions.Slot.HEAD, "IRON_HELMET",
                SpawnOptions.Slot.CHEST, "DIAMOND_CHESTPLATE",
                SpawnOptions.Slot.MAIN_HAND, "STICK"), options.equipment());
        assertEquals(Map.of(), SpawnOptions.NONE.equipment());
    }

    @Test void unknownOrMalformedTokensAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SpawnOptions.parse("flying", 60));
        assertThrows(IllegalArgumentException.class, () -> SpawnOptions.parse("hp:many", 60));
        assertThrows(IllegalArgumentException.class, () -> SpawnOptions.parse("name:", 60));
        assertThrows(IllegalArgumentException.class, () -> SpawnOptions.parse("t", 60));
        assertThrows(IllegalArgumentException.class, () -> SpawnOptions.parse("name{unclosed", 60));
        assertThrows(IllegalArgumentException.class, () -> SpawnOptions.parse("head", 60));
        assertEquals("flying", assertThrows(IllegalArgumentException.class,
                () -> SpawnOptions.parse("flying", 60)).getMessage());
    }

    @Test void lifetimeIsOnlySetWhenPositive() {
        assertFalse(SpawnOptions.parse("hp:20", 60).hasLifetime());
        assertTrue(SpawnOptions.parse("t:1", 60).hasLifetime());
    }
}
