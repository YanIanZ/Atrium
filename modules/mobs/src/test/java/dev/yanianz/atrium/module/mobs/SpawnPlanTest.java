package dev.yanianz.atrium.module.mobs;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpawnPlanTest {
    @Test void splitsStacksAndRejectsEmptySegments() {
        assertEquals(List.of("spider", "skeleton"), SpawnPlan.tokens("Spider+SKELETON"));
        assertEquals(List.of("zombie"), SpawnPlan.tokens("zombie"));
        assertThrows(IllegalArgumentException.class, () -> SpawnPlan.tokens("spider++skeleton"));
        assertThrows(IllegalArgumentException.class, () -> SpawnPlan.tokens("spider+"));
        assertThrows(IllegalArgumentException.class, () -> SpawnPlan.tokens(""));
    }

    @Test void resolveReportsFirstUnknownToken() {
        Map<String, Integer> known = Map.of("a", 1, "b", 2);
        var ok = SpawnPlan.resolve(List.of("a", "b", "a"), t -> Optional.ofNullable(known.get(t)));
        assertTrue(ok.ok());
        assertEquals(List.of(1, 2, 1), ok.values());
        var bad = SpawnPlan.resolve(List.of("a", "x", "y"), t -> Optional.ofNullable(known.get(t)));
        assertFalse(bad.ok());
        assertEquals("x", bad.unknown());
    }

    @Test void unboundedRequestsAreCappedByTotal() {
        // Unbounded riders: 25 x (1 + 15 x 10) = ~3.8k entities from one command.
        SpawnPlan.Plan plan = SpawnPlan.plan(25, 10, 50, 0, 10_000);
        assertEquals(5, plan.stacks());
        assertEquals(50, plan.entities());
        assertEquals(SpawnPlan.Limit.TOTAL, plan.limitedBy());
    }

    @Test void chunkCapLeavesRoomOnlyForWholeStacks() {
        SpawnPlan.Plan plan = SpawnPlan.plan(10, 3, 100, 74, 80);
        assertEquals(2, plan.stacks()); // room 6 -> two stacks of three
        assertEquals(SpawnPlan.Limit.CHUNK, plan.limitedBy());
        SpawnPlan.Plan full = SpawnPlan.plan(1, 1, 100, 80, 80);
        assertEquals(0, full.stacks());
        assertEquals(0, SpawnPlan.plan(1, 2, 100, 500, 80).stacks());
    }

    @Test void withinLimitsSpawnsEverything() {
        SpawnPlan.Plan plan = SpawnPlan.plan(4, 2, 50, 10, 80);
        assertEquals(4, plan.stacks());
        assertEquals(8, plan.entities());
        assertEquals(SpawnPlan.Limit.NONE, plan.limitedBy());
    }

    @Test void neverExceedsAnyBound() {
        for (int requested = 1; requested <= 30; requested++) {
            for (int depth = 1; depth <= 5; depth++) {
                for (int existing = 0; existing <= 90; existing += 7) {
                    SpawnPlan.Plan plan = SpawnPlan.plan(requested, depth, 50, existing, 80);
                    assertTrue(plan.stacks() >= 0 && plan.stacks() <= requested);
                    assertTrue(plan.entities() <= 50);
                    assertTrue(existing + plan.entities() <= Math.max(80, existing));
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> SpawnPlan.plan(0, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> SpawnPlan.plan(1, 0, 1, 0, 1));
    }

    @Test void chunkAreaCoversCircleOnly() {
        List<ChunkArea.ChunkPos> one = ChunkArea.around(8, 8, 0);
        assertEquals(List.of(new ChunkArea.ChunkPos(0, 0)), one);
        List<ChunkArea.ChunkPos> area = ChunkArea.around(0, 0, 40);
        assertTrue(area.contains(new ChunkArea.ChunkPos(-1, -1)));
        assertTrue(area.contains(new ChunkArea.ChunkPos(2, 0)));
        assertFalse(area.contains(new ChunkArea.ChunkPos(-3, -3)), "far corner is outside the circle");
        assertEquals(area.size(), area.stream().distinct().count());
        assertTrue(ChunkArea.around(-1, -1, 2).contains(new ChunkArea.ChunkPos(0, 0)));
        assertFalse(ChunkArea.around(-1, -1, 1).contains(new ChunkArea.ChunkPos(0, 0)), "diagonal neighbour is sqrt(2) away");
        assertThrows(IllegalArgumentException.class, () -> ChunkArea.around(0, 0, -1));
    }
}
