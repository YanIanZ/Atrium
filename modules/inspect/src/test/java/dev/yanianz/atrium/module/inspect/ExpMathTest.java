package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExpMathTest {
    @Test
    void cumulativeCostMatchesTheVanillaTable() {
        assertEquals(0, ExpMath.expToReachLevel(0));
        assertEquals(7, ExpMath.expToReachLevel(1));
        assertEquals(352, ExpMath.expToReachLevel(16));
        assertEquals(394, ExpMath.expToReachLevel(17));
        assertEquals(1507, ExpMath.expToReachLevel(31));
        assertEquals(1628, ExpMath.expToReachLevel(32));
        assertEquals(30_970, ExpMath.expToReachLevel(100));
    }

    @Test
    void nextLevelCostChangesAtTheVanillaThresholds() {
        assertEquals(7, ExpMath.expToNextLevel(0));
        assertEquals(37, ExpMath.expToNextLevel(15));
        assertEquals(42, ExpMath.expToNextLevel(16));
        assertEquals(112, ExpMath.expToNextLevel(30));
        assertEquals(121, ExpMath.expToNextLevel(31));
        assertEquals(130, ExpMath.expToNextLevel(32));
        assertEquals(0, ExpMath.expToNextLevel(-1));
    }

    @Test
    void totalExperienceUsesTheProgressBar() {
        assertEquals(1507, ExpMath.totalExp(31, 0));
        assertEquals(1567, ExpMath.totalExp(31, 0.5));
        assertEquals(1628, ExpMath.totalExp(31, 1));
        assertEquals(0, ExpMath.totalExp(0, 0));
        assertEquals(7, ExpMath.totalExp(0, 1));
        // Out-of-range progress is clamped, never negative or above the next level.
        assertEquals(0, ExpMath.totalExp(0, -3));
        assertEquals(7, ExpMath.totalExp(0, 4));
    }

    @Test
    void levelForTotalIsTheInverseAtEveryLevelBoundary() {
        for (int level = 0; level <= 200; level++) {
            long start = ExpMath.expToReachLevel(level);
            assertEquals(level, ExpMath.levelForTotal(start), "level " + level);
            if (level > 0) {
                assertEquals(level - 1, ExpMath.levelForTotal(start - 1), "just before level " + level);
            }
        }
    }

    @Test
    void progressForTotalRoundTripsWithTotalExp() {
        for (int level = 0; level <= 50; level++) {
            for (double progress : new double[] {0, 0.25, 0.5, 0.999}) {
                long total = ExpMath.totalExp(level, progress);
                assertEquals(level, ExpMath.levelForTotal(total), "level at " + level + "/" + progress);
                double back = ExpMath.progressForTotal(total);
                assertTrue(back >= 0 && back <= 1, "progress in range");
            }
        }
    }

    @Test
    void negativeTotalsAreLevelZero() {
        assertEquals(0, ExpMath.levelForTotal(-1));
        assertEquals(0, ExpMath.progressForTotal(-1));
    }
}
