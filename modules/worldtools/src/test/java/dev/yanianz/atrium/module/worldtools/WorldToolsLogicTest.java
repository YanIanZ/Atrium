package dev.yanianz.atrium.module.worldtools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class WorldToolsLogicTest {
    private static final Map<String, String> BLOCKS = Map.of("stone", "STONE", "diamond_ore", "DIAMOND_ORE",
            "gold_ore", "GOLD_ORE", "air", "AIR");

    private static Optional<String> block(String name) {
        return Optional.ofNullable(BLOCKS.get(name));
    }

    @Test void ringsVisitEveryChunkOnceInnermostFirst() {
        for (int radius = 0; radius <= 6; radius++) {
            List<ChunkOrder.ChunkPos> order = ChunkOrder.rings(3, -2, radius);
            assertEquals((2 * radius + 1) * (2 * radius + 1), order.size());
            assertEquals(order.size(), new HashSet<>(order).size(), "no duplicates");
            assertEquals(new ChunkOrder.ChunkPos(3, -2), order.getFirst());
            int previous = 0;
            for (ChunkOrder.ChunkPos pos : order) {
                int ring = Math.max(Math.abs(pos.x() - 3), Math.abs(pos.z() + 2));
                assertTrue(ring >= previous, "rings never go back inwards");
                assertTrue(ring <= radius);
                previous = ring;
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ChunkOrder.rings(0, 0, -1));
    }

    @Test void coveringStaysInsideTheBox() {
        // Box from block -20..20 / -5..40 around block (0, 10): chunks -2..1 x -1..2.
        List<ChunkOrder.ChunkPos> chunks = ChunkOrder.covering(0, 10, -20, 20, -5, 40);
        assertEquals(16, chunks.size());
        assertEquals(new ChunkOrder.ChunkPos(0, 0), chunks.getFirst());
        for (ChunkOrder.ChunkPos pos : chunks) {
            assertTrue(pos.x() >= -2 && pos.x() <= 1 && pos.z() >= -1 && pos.z() <= 2, pos.toString());
        }
    }

    @Test void speedNeverDropsToZeroOrBelow() {
        int speed = 5;
        for (int i = 0; i < 100; i++) {
            speed = SpeedControl.next(speed, 1000, 0, 8);
            assertTrue(speed >= 1, "speed " + speed);
        }
        assertEquals(1, speed);
        assertEquals(1, SpeedControl.next(-50, 7, -3, 8));
        assertEquals(1, SpeedControl.floor(0));
        assertEquals(1, SpeedControl.floor(-10));
        // ramps up when idle, capped at max
        speed = 1;
        for (int i = 0; i < 50; i++) {
            speed = SpeedControl.next(speed, 0, 1, 8);
        }
        assertEquals(8, speed);
        // steady when the backlog matches the speed
        assertEquals(4, SpeedControl.next(4, 4, 1, 8));
        // max below min collapses to min
        assertEquals(3, SpeedControl.next(10, 0, 3, 1));
    }

    @Test void dispatchIsBoundedBySpeedAndBacklog() {
        assertEquals(4, SpeedControl.dispatch(4, 0));
        assertEquals(1, SpeedControl.dispatch(4, 3));
        assertEquals(0, SpeedControl.dispatch(4, 9));
        assertEquals(1, SpeedControl.dispatch(0, 0), "a zero speed still dispatches");
        assertEquals(1, SpeedControl.dispatch(-5, -1));
    }

    @Test void failedRollsNeverTurnIntoAir() {
        // Regression guard: "diamond_ore%5" must not turn ~95% of the matched blocks into air.
        var choices = Replacement.parse("diamond_ore%5", WorldToolsLogicTest::block);
        SplittableRandom random = new SplittableRandom(7);
        int replaced = 0;
        for (int i = 0; i < 100_000; i++) {
            Optional<String> pick = Replacement.pick(choices, random);
            if (pick.isPresent()) {
                assertEquals("DIAMOND_ORE", pick.get());
                replaced++;
            }
        }
        assertTrue(replaced > 4_000 && replaced < 6_000, "about 5% replaced: " + replaced);
        // Several low chances: still only listed values or "unchanged".
        var many = Replacement.parse("diamond_ore%1+gold_ore%2", WorldToolsLogicTest::block);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50_000; i++) {
            Replacement.pick(many, random).ifPresent(seen::add);
        }
        assertEquals(Set.of("DIAMOND_ORE", "GOLD_ORE"), seen);
        assertFalse(seen.contains("AIR"));
    }

    @Test void fullChanceAlwaysWinsAndFirstPassingEntryIsUsed() {
        var choices = Replacement.parse("stone+diamond_ore%50", WorldToolsLogicTest::block);
        SplittableRandom random = new SplittableRandom(1);
        for (int i = 0; i < 1000; i++) {
            assertEquals(Optional.of("STONE"), Replacement.pick(choices, random));
        }
        // Explicit air is allowed only when listed.
        assertEquals(Optional.of("AIR"), Replacement.pick(Replacement.parse("air", WorldToolsLogicTest::block), random));
    }

    @Test void parseRejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Replacement.parse("", WorldToolsLogicTest::block));
        assertThrows(IllegalArgumentException.class, () -> Replacement.parse("stone%0", WorldToolsLogicTest::block));
        assertThrows(IllegalArgumentException.class, () -> Replacement.parse("stone%150", WorldToolsLogicTest::block));
        assertThrows(IllegalArgumentException.class, () -> Replacement.parse("stone%abc", WorldToolsLogicTest::block));
        assertThrows(IllegalArgumentException.class, () -> Replacement.parse("stone++gold_ore", WorldToolsLogicTest::block));
        assertThrows(IllegalArgumentException.class, () -> Replacement.parse("bedrockx", WorldToolsLogicTest::block));
        assertEquals(2, Replacement.parse(" STONE %12.5 , gold_ore", WorldToolsLogicTest::block).size());
        assertEquals(12.5, Replacement.parse("stone%12.5", WorldToolsLogicTest::block).getFirst().chance());
    }

    @Test void chunkSeedsDifferBetweenNeighbours() {
        Set<Long> seeds = new HashSet<>();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                seeds.add(Replacement.chunkSeed(12345L, x, z));
            }
        }
        assertEquals(17 * 17, seeds.size());
        assertNotEquals(Replacement.chunkSeed(1, 0, 0), Replacement.chunkSeed(2, 0, 0));
    }
}
