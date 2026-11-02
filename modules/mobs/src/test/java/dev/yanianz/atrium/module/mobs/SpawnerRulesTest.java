package dev.yanianz.atrium.module.mobs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SpawnerRulesTest {
    private static final long SECOND = 1000L;

    @Test void chargesRefillOnePerPeriod() {
        SpawnerChargeState start = SpawnerChargeState.initial(0, 0);
        assertEquals(0, start.refill(3, 60, 59 * SECOND).charges());
        SpawnerChargeState one = start.refill(3, 60, 60 * SECOND);
        assertEquals(1, one.charges());
        assertEquals(60 * SECOND, one.lastGiveMillis());
        SpawnerChargeState two = one.refill(3, 60, 200 * SECOND);
        assertEquals(3, two.charges(), "three periods elapsed, capped at maximum");
    }

    @Test void stayingAtTheCapDoesNotBankAFreeCharge() {
        SpawnerChargeState full = SpawnerChargeState.initial(3, 0);
        SpawnerChargeState later = full.refill(3, 60, 10 * 60 * SECOND);
        assertEquals(3, later.charges());
        assertEquals(10 * 60 * SECOND, later.lastGiveMillis(), "reference moves with the clock at the cap");
        SpawnerChargeState spent = later.spend();
        assertEquals(2, spent.charges());
        assertEquals(2, spent.refill(3, 60, 10 * 60 * SECOND + 30 * SECOND).charges(),
                "no instant refill after spending");
    }

    @Test void zeroCooldownDoesNotDivideByZero() {
        SpawnerChargeState state = SpawnerChargeState.initial(1, 0).refill(2, 0, 5 * SECOND);
        assertEquals(2, state.charges());
        assertTrue(state.secondsUntilNext(2, 0, 5 * SECOND) >= 0);
    }

    @Test void backwardsClockNeverRefills() {
        SpawnerChargeState state = new SpawnerChargeState(1, 10 * SECOND);
        assertEquals(1, state.refill(3, 60, 0).charges());
    }

    @Test void spendNeverGoesNegative() {
        SpawnerChargeState empty = new SpawnerChargeState(0, 0);
        assertFalse(empty.hasCharge());
        assertEquals(0, empty.spend().charges());
    }

    @Test void secondsUntilNextIsRoundedUp() {
        SpawnerChargeState state = new SpawnerChargeState(0, 0);
        assertEquals(59, state.secondsUntilNext(3, 60, SECOND));
        assertEquals(1, state.secondsUntilNext(3, 60, 59 * SECOND + 1));
        assertEquals(0, new SpawnerChargeState(3, 0).secondsUntilNext(3, 60, 0));
    }

    @Test void dropChanceHonoursSilkTouchAndIsClamped() {
        assertEquals(0, SpawnerDropRules.chance(100, false, true));
        assertEquals(100, SpawnerDropRules.chance(100, true, true));
        assertEquals(40, SpawnerDropRules.chance(40, false, false));
        assertEquals(100, SpawnerDropRules.chance(500, false, false));
        assertEquals(0, SpawnerDropRules.chance(-20, false, false));
        assertEquals(0, SpawnerDropRules.chance(Double.NaN, false, false));
    }

    @Test void rollUsesStrictComparison() {
        assertFalse(SpawnerDropRules.drops(0, 0), "chance 0 never drops");
        assertTrue(SpawnerDropRules.drops(100, 99.999), "chance 100 always drops");
        assertTrue(SpawnerDropRules.drops(50, 49.999));
        assertFalse(SpawnerDropRules.drops(50, 50));
        assertFalse(SpawnerDropRules.drops(50, 50.001));
    }

    @Test void editorValuesAreClamped() {
        SpawnerSettings base = new SpawnerSettings(20, 200, 800, 4, 16, 4, 6, "zombie");
        assertEquals(1, base.shift(SpawnerSettings.Field.SPAWN_COUNT, -100).spawnCount());
        assertEquals(32, base.shift(SpawnerSettings.Field.SPAWN_COUNT, 100).spawnCount());
        assertEquals(32, base.shift(SpawnerSettings.Field.PLAYER_RANGE, 100).playerRange());
        assertEquals(200, base.shift(SpawnerSettings.Field.MAX_NEARBY, 1000).maxNearby());
        assertEquals(0, base.shift(SpawnerSettings.Field.DELAY, -1000).delay());
    }

    @Test void editorKeepsMinimumBelowMaximum() {
        SpawnerSettings base = new SpawnerSettings(20, 200, 800, 4, 16, 4, 6, null);
        SpawnerSettings min = base.shift(SpawnerSettings.Field.MIN_DELAY, 10_000);
        assertEquals(min.maxDelay(), min.minDelay());
        SpawnerSettings max = base.shift(SpawnerSettings.Field.MAX_DELAY, -10_000);
        assertEquals(max.minDelay(), max.maxDelay());
        assertEquals("zombie", base.withType("zombie").typeKey());
    }

    @Test void proximityFindsNearestOtherSpawnerOnly() {
        List<SpawnerProximity.Pos> spawners = List.of(
                new SpawnerProximity.Pos(0, 64, 0),
                new SpawnerProximity.Pos(3, 64, 4),
                new SpawnerProximity.Pos(20, 64, 0));
        assertEquals(25, SpawnerProximity.nearest(0, 64, 0, 10, spawners));
        assertEquals(-1, SpawnerProximity.nearest(0, 64, 0, 4, spawners));
        assertEquals(-1, SpawnerProximity.nearest(0, 64, 0, 10, List.of(new SpawnerProximity.Pos(0, 64, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> SpawnerProximity.nearest(0, 64, 0, -1, spawners));
    }
}
