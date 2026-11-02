package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ItemCooldownTest {
    @Test
    void secondsAreClampedIntoTheSupportedRange() {
        assertEquals(0, ItemCooldown.of(-5, "id").seconds());
        assertEquals(ItemCooldown.MAX_SECONDS, ItemCooldown.of(ItemCooldown.MAX_SECONDS + 1, "id").seconds());
        assertEquals(30, ItemCooldown.of(30, "id").seconds());
        assertThrows(IllegalArgumentException.class, () -> new ItemCooldown.Metadata(-1, "id"));
        assertThrows(IllegalArgumentException.class, () -> new ItemCooldown.Metadata(1, ""));
    }

    @Test
    void idsAreNormalisedForMultiCopyItems() {
        assertEquals("tnt", ItemCooldown.normalizeId(" TNT "));
        assertEquals("my_rocket", ItemCooldown.normalizeId("My_Rocket"));
        assertEquals("a.b:c-d", ItemCooldown.normalizeId("a.b:c-d"));
        assertEquals("snakecase", ItemCooldown.normalizeId("Snake Case"));
        assertEquals(ItemCooldown.DEFAULT_ID, ItemCooldown.normalizeId("!!!"));
        assertEquals(ItemCooldown.DEFAULT_ID, ItemCooldown.normalizeId(null));
        assertEquals(64, ItemCooldown.normalizeId("x".repeat(200)).length());
        assertEquals(ItemCooldown.DEFAULT_ID, ItemCooldown.parseId(java.util.Optional.empty()));
        assertEquals("rocket", ItemCooldown.parseId(java.util.Optional.of("Rocket")));
    }

    @Test
    void remainingSecondsRoundUpSoAFractionStillReadsAsOne() {
        assertEquals(0, ItemCooldown.remainingSeconds(1_000, 1_000));
        assertEquals(0, ItemCooldown.remainingSeconds(2_000, 1_000));
        assertEquals(1, ItemCooldown.remainingSeconds(1_000, 1_001));
        assertEquals(1, ItemCooldown.remainingSeconds(0, 999));
        assertEquals(60, ItemCooldown.remainingSeconds(0, 60_000));
    }

    @Test
    void readyIsInclusiveAndTheExpiryCannotOverflow() {
        assertTrue(ItemCooldown.ready(5, 5));
        assertFalse(ItemCooldown.ready(4, 5));
        assertEquals(31_000, ItemCooldown.readyAt(1_000, 30));
        assertEquals(Long.MAX_VALUE, ItemCooldown.readyAt(Long.MAX_VALUE - 5, 30));
        assertEquals(0, ItemCooldown.remainingSeconds(5, 0));
    }
}
