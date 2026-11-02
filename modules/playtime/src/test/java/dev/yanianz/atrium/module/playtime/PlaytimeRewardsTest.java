package dev.yanianz.atrium.module.playtime;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlaytimeRewardsTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);
    /** No cap for the classic schedule tests; the cap has its own test. */
    private static final int NO_CAP = 1_000;

    private static PlaytimeRewards.Spec spec(PlaytimeRewards.Type type, long seconds) {
        return new PlaytimeRewards.Spec("test", type, seconds, "", List.of(), BigDecimal.ZERO);
    }

    private static PlaytimeRewards.Plan plan(PlaytimeRewards.Spec spec, Long watermark, long total, long today) {
        return PlaytimeRewards.plan(spec, watermark, total, today, DAY, NO_CAP);
    }

    @Test void oneTimePaysExactlyOnceAtThreshold() {
        var reward = spec(PlaytimeRewards.Type.ONE_TIME, 3600);
        assertTrue(plan(reward, null, 3599, 0).empty());
        var first = plan(reward, null, 3600, 0);
        assertEquals(List.of(new PlaytimeRewards.Grant("test", PlaytimeRewards.Type.ONE_TIME, 1)), first.grants());
        assertTrue(plan(reward, 1L, 100_000, 0).empty());
    }

    @Test void repeatingBaselinesOnFirstSightAndThenPaysEveryMissedInterval() {
        var reward = spec(PlaytimeRewards.Type.REPEATING, 60);
        var baseline = plan(reward, null, 150, 0);
        assertTrue(baseline.grants().isEmpty());
        assertEquals(2L, baseline.baseline(), "install must not pay a backlog");

        var due = plan(reward, 2L, 360, 0);
        assertEquals(List.of(3L, 4L, 5L, 6L), due.grants().stream().map(PlaytimeRewards.Grant::mark).toList());
        assertTrue(plan(reward, 6L, 360, 0).empty());
        assertTrue(plan(reward, 6L, 359, 0).empty());
    }

    @Test void grantsPerCheckAreCappedAndContinueOnTheNextCheck() {
        var reward = spec(PlaytimeRewards.Type.REPEATING, 60);
        var capped = PlaytimeRewards.plan(reward, 0L, 600, 0, DAY, 1);
        assertEquals(List.of(1L), capped.grants().stream().map(PlaytimeRewards.Grant::mark).toList(),
                "defence in depth: a stale watermark must not burst out in one check");

        var three = PlaytimeRewards.plan(reward, 0L, 600, 0, DAY, 3);
        assertEquals(List.of(1L, 2L, 3L), three.grants().stream().map(PlaytimeRewards.Grant::mark).toList());

        var next = PlaytimeRewards.plan(reward, 1L, 600, 0, DAY, 2);
        assertEquals(List.of(2L, 3L), next.grants().stream().map(PlaytimeRewards.Grant::mark).toList(),
                "the remainder stays owed and is paid later");
        assertEquals(List.of(1L),
                PlaytimeRewards.plan(reward, 0L, 600, 0, DAY, 0).grants().stream()
                        .map(PlaytimeRewards.Grant::mark).toList(),
                "an invalid cap is clamped to one, never unbounded");
    }

    @Test void anAdministrativeJumpNeverPaysAnIntervalBacklog() {
        var hourly = spec(PlaytimeRewards.Type.REPEATING, 3600);
        long imported = 2_000L * 3600L;

        // The old code planned 2000 grants here (one per imported hour).
        assertEquals(2_000L, PlaytimeRewards.advanceTo(hourly, imported));
        assertTrue(PlaytimeRewards.plan(hourly, PlaytimeRewards.advanceTo(hourly, imported), imported, 0, DAY, 1)
                .empty(), "an imported total must not pay a backlog");

        // A partial interval does not hand out a full one either.
        assertEquals(0L, PlaytimeRewards.advanceTo(spec(PlaytimeRewards.Type.REPEATING, 3600), 3_599));
        // Non-interval rewards have no watermark to advance.
        assertNull(PlaytimeRewards.advanceTo(spec(PlaytimeRewards.Type.ONE_TIME, 3600), imported));
        assertNull(PlaytimeRewards.advanceTo(spec(PlaytimeRewards.Type.DAILY, 60), imported));
    }

    @Test void dailyUsesTodaySecondsAndOneMarkPerDay() {
        var reward = spec(PlaytimeRewards.Type.DAILY, 600);
        assertTrue(plan(reward, null, 0, 599).empty());
        var grant = plan(reward, null, 0, 600).grants().getFirst();
        assertEquals(DAY.toEpochDay(), grant.mark());
        assertTrue(plan(reward, DAY.toEpochDay(), 0, 9999).empty());
        assertFalse(PlaytimeRewards.plan(reward, DAY.toEpochDay(), 0, 600, DAY.plusDays(1), NO_CAP).empty());
    }

    @Test void zeroSecondDailyRewardIsAvailableEveryDay() {
        var reward = spec(PlaytimeRewards.Type.DAILY, 0);
        assertFalse(plan(reward, null, 0, 0).empty());
        assertFalse(PlaytimeRewards.plan(reward, DAY.toEpochDay(), 0, 0, DAY.plusDays(1), NO_CAP).empty());
    }

    @Test void rejectsUnsafeRewardDefinitions() {
        PlaytimeConfig.Reward reward = new PlaytimeConfig.Reward();
        reward.id = "bad id";
        assertThrows(IllegalArgumentException.class, () -> PlaytimeRewards.spec(reward));
        reward.id = "ok";
        reward.seconds = 1;
        assertThrows(IllegalArgumentException.class, () -> PlaytimeRewards.spec(reward));
        reward.seconds = 60;
        reward.commands = List.of("give {player} stone\nop {player}");
        assertThrows(IllegalArgumentException.class, () -> PlaytimeRewards.spec(reward));
        reward.commands = List.of();
        reward.money = "-1";
        assertThrows(IllegalArgumentException.class, () -> PlaytimeRewards.spec(reward));
    }

    @Test void normalisesRewardTypesAndMoney() {
        PlaytimeConfig.Reward reward = new PlaytimeConfig.Reward();
        reward.id = "hourly";
        reward.type = "repeating";
        reward.seconds = 120;
        reward.money = "12.50";
        var spec = PlaytimeRewards.spec(reward);
        assertEquals(PlaytimeRewards.Type.REPEATING, spec.type());
        assertEquals(new BigDecimal("12.50"), spec.money());
    }
}
