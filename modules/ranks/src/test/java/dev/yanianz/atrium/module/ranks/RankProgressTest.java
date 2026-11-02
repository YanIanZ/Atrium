package dev.yanianz.atrium.module.ranks;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class RankProgressTest {
    private static RankLadder.Rank rank(long playtime, String money, String permission) {
        return new RankLadder.Rank("id", "Rank", "group", playtime, new BigDecimal(money), permission,
                List.of(), true);
    }

    @Test void reportsEachRequirementIndependently() {
        var rank = rank(3600, "100", "rank.test");
        var progress = new RankProgress(rank, 1800, new BigDecimal("50"), false);
        assertFalse(progress.playtimeMet());
        assertFalse(progress.moneyMet());
        assertFalse(progress.permissionMet());
        assertFalse(progress.met());
        assertEquals(33, progress.percent(), "50% playtime and 50% money, 0% permission");

        var ready = new RankProgress(rank, 3600, new BigDecimal("100"), true);
        assertTrue(ready.met());
        assertEquals(100, ready.percent());
    }

    @Test void percentAveragesOnlyExistingRequirementsAndCapsEachAtHundred() {
        var rank = rank(100, "100", "");
        var progress = new RankProgress(rank, 100, new BigDecimal("1000"), true);
        assertEquals(100, progress.percent(), "excess money must not inflate a missing other requirement");

        var half = new RankProgress(rank, 50, new BigDecimal("50"), true);
        assertEquals(50, half.percent());
    }

    @Test void rankWithoutRequirementsIsAlwaysMet() {
        var rank = rank(0, "0", "");
        var progress = new RankProgress(rank, 0, BigDecimal.ZERO, false);
        assertTrue(progress.met());
        assertEquals(100, progress.percent());
    }
}
