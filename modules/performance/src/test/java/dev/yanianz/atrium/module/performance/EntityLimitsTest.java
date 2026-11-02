package dev.yanianz.atrium.module.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EntityLimitsTest {
    @Test void cheapPathNeedsStrictlyLessThanHalfTheCap() {
        assertTrue(EntityLimits.underHalf(0, 10));
        assertTrue(EntityLimits.underHalf(4, 10));
        assertFalse(EntityLimits.underHalf(5, 10));
        assertFalse(EntityLimits.underHalf(6, 10));
        assertTrue(EntityLimits.underHalf(0, 1), "an empty chunk is always cheap");
        assertTrue(EntityLimits.underHalf(1, 3), "1 is below half of 3");
        assertFalse(EntityLimits.underHalf(2, 3));
    }

    @Test void capBoundariesAreExact() {
        assertTrue(EntityLimits.allowed(0, 3));
        assertTrue(EntityLimits.allowed(2, 3));
        assertFalse(EntityLimits.allowed(3, 3));
        assertFalse(EntityLimits.allowed(4, 3));
        assertTrue(EntityLimits.allowed(100, 0), "0 disables the cap");
        assertTrue(EntityLimits.allowed(100, -1));
    }

    @Test void statsAddUpPerCategory() {
        EntityLimitStats stats = new EntityLimitStats();
        stats.record(EntityCategory.ANIMALS);
        stats.record(EntityCategory.ANIMALS);
        stats.record(EntityCategory.ITEMS);
        EntityLimitStats.Snapshot snapshot = stats.snapshot();
        assertEquals(3, snapshot.total());
        assertEquals(2, snapshot.animals());
        assertEquals(1, snapshot.items());
        assertEquals(0, snapshot.monsters());
        assertEquals(0, snapshot.minecarts());
    }
}
