package dev.yanianz.atrium.module.vanish;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VisibilityRulesTest {
    @Test void everyoneSeesVisiblePlayersAndThemselves() {
        assertTrue(VisibilityRules.canSee(false, false, 0, 5));
        assertTrue(VisibilityRules.canSee(true, true, 0, 5));
    }

    @Test void withoutSeePermissionVanishedPlayersAreHidden() {
        int level = VisibilityRules.seeLevel(false, 10);
        assertEquals(0, level);
        assertFalse(VisibilityRules.canSee(false, true, level, 1));
    }

    @Test void seeLevelMustReachTheVanishLevel() {
        int viewer = VisibilityRules.seeLevel(true, 1);
        assertEquals(1, viewer);
        assertTrue(VisibilityRules.canSee(false, true, viewer, 1));
        assertFalse(VisibilityRules.canSee(false, true, viewer, 2));
        assertTrue(VisibilityRules.canSee(false, true, VisibilityRules.seeLevel(true, 3), 3));
        assertTrue(VisibilityRules.canSee(false, true, VisibilityRules.seeLevel(true, Integer.MAX_VALUE), Integer.MAX_VALUE));
        assertFalse(VisibilityRules.canSee(false, true, VisibilityRules.seeLevel(true, 99), Integer.MAX_VALUE));
    }

    @Test void levelsBelowOneAreTreatedAsOne() {
        assertEquals(1, VisibilityRules.vanishLevel(0));
        assertEquals(1, VisibilityRules.seeLevel(true, -4));
        assertFalse(VisibilityRules.canSee(false, true, 0, 0));
    }

    @Test void serverListCountNeverNegative() {
        assertEquals(3, VisibilityRules.visibleCount(5, 2));
        assertEquals(0, VisibilityRules.visibleCount(1, 4));
        assertEquals(5, VisibilityRules.visibleCount(5, -1));
    }
}
