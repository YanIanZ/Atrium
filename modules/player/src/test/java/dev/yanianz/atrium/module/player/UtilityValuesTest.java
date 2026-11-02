package dev.yanianz.atrium.module.player;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UtilityValuesTest {
    @Test void soundArgumentsAreNamespacedOrEnumShaped() {
        assertEquals(new UtilityValues.SoundInput("BLOCK_NOTE_BLOCK_PLING", false),
                UtilityValues.sound("BLOCK_NOTE_BLOCK_PLING"));
        assertEquals(new UtilityValues.SoundInput("minecraft:block.note_block.pling", true),
                UtilityValues.sound("block.note_block.pling"));
        assertEquals(new UtilityValues.SoundInput("minecraft:block_note_block_pling", true),
                UtilityValues.sound("block_note_block_pling"));
        assertEquals(new UtilityValues.SoundInput("minecraft:entity.player.levelup", true),
                UtilityValues.sound("minecraft:Entity.Player.LevelUp"));
        for (String invalid : List.of("", "two words", "minecraft:", "other:block.note", "block note", "a;b")) {
            assertThrows(IllegalArgumentException.class, () -> UtilityValues.sound(invalid), invalid);
        }
    }

    @Test void numbersAreLocaleIndependentAndCompact() {
        assertEquals("4", UtilityValues.number(4.0));
        assertEquals("2.5", UtilityValues.number(2.5));
        assertEquals("0", UtilityValues.number(-0.0));
        assertEquals("0.1", UtilityValues.number(0.1));
        assertThrows(IllegalArgumentException.class, () -> UtilityValues.number(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> UtilityValues.number(Double.POSITIVE_INFINITY));
    }

    @Test void fallDistanceAndShootSpeedRejectOutOfRangeValues() {
        assertEquals(12.5f, UtilityValues.fallDistance(12.5));
        assertThrows(IllegalArgumentException.class, () -> UtilityValues.fallDistance(-1));
        assertThrows(IllegalArgumentException.class, () -> UtilityValues.fallDistance(10_001));
        assertThrows(IllegalArgumentException.class, () -> UtilityValues.fallDistance(Double.NaN));
        assertEquals(2.0, UtilityValues.shootSpeed(2.0));
        assertThrows(IllegalArgumentException.class, () -> UtilityValues.shootSpeed(-0.1));
        assertThrows(IllegalArgumentException.class, () -> UtilityValues.shootSpeed(5.1));
    }

    @Test void materialKeysAreLowerCasedAndTrimmed() {
        assertEquals("minecraft:pumpkin", UtilityValues.materialKey("  Minecraft:Pumpkin "));
        assertEquals("", UtilityValues.materialKey(null));
        assertEquals("30", UtilityValues.seconds(30));
    }
}
