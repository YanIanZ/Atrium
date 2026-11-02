package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EntityFormatTest {
    @Test
    void horseJumpHeightUsesTheVanillaRelation() {
        assertEquals(0.0, EntityFormat.jumpHeight(0.0));
        assertEquals(0.0, EntityFormat.jumpHeight(-1.0));
        assertEquals(5.293, EntityFormat.jumpHeight(1.0), 0.0001);
        assertTrue(EntityFormat.jumpHeight(0.8) > EntityFormat.jumpHeight(0.6));
        assertEquals(5.293, EntityFormat.jumpHeight(50.0), 0.0001);
    }

    @Test
    void blocksAreFormattedWithoutLocaleNoise() {
        assertEquals("1.5 blocks", EntityFormat.blocks(1.5));
        assertEquals("2 blocks", EntityFormat.blocks(2.0));
        assertEquals("?", EntityFormat.blocks(Double.NaN));
    }

    @Test
    void prettyKeysAreReadable() {
        assertEquals("Walk One Cm", InspectFormat.prettyKey("walk_one_cm"));
        assertEquals("Kill Entity", InspectFormat.prettyKey("kill_entity"));
        assertEquals("", InspectFormat.prettyKey(""));
    }
}
