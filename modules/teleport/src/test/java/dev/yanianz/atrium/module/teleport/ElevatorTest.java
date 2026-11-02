package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ElevatorTest {
    /** Stops at these Y levels, everything else is air. */
    private static IntPredicate stops(int... ys) {
        Set<Integer> set = Arrays.stream(ys).boxed().collect(Collectors.toSet());
        return set::contains;
    }

    @Test
    void risingSkipsCloseAndLowStops() {
        IntPredicate stop = stops(10, 12, 20, 40);
        // Clicked at 10: 11 is too close (gap 2), 12 is the first stop.
        assertEquals(OptionalInt.of(12), Elevator.above(10, 2, 64, stop));
        assertEquals(OptionalInt.of(40), Elevator.above(21, 2, 64, stop));
    }

    @Test
    void risingStopsAtTheCeiling() {
        assertEquals(OptionalInt.empty(), Elevator.above(10, 2, 15, stops(20)));
        assertEquals(OptionalInt.empty(), Elevator.above(40, 2, 64, stops(10, 12)));
    }

    @Test
    void sinkingUsesTheNearestStopBelow() {
        IntPredicate stop = stops(10, 12, 20, 40);
        assertEquals(OptionalInt.of(20), Elevator.below(40, 2, 0, stop));
        assertEquals(OptionalInt.of(12), Elevator.below(21, 2, 0, stop));
        assertEquals(OptionalInt.empty(), Elevator.below(11, 2, 0, stops(10, 12)));
        assertEquals(OptionalInt.empty(), Elevator.below(40, 2, 45, stop));
    }

    @Test
    void gapIsAlwaysAtLeastOneBlock() {
        IntPredicate stop = stops(11, 12, 13);
        assertEquals(OptionalInt.of(11), Elevator.above(10, 0, 64, stop));
        assertEquals(OptionalInt.of(12), Elevator.below(13, 0, 0, stop));
    }

    @Test
    void matchingUsesTheConfiguredPatterns() {
        List<String> patterns = List.of("*_SIGN");
        assertTrue(Elevator.matches(patterns, "BIRCH_SIGN"));
        assertFalse(Elevator.matches(patterns, "LODESTONE"));
    }

    @Test
    void landingWindowStaysOnTheSameFloor() {
        TeleportConfig.SafeLanding global = new TeleportConfig.SafeLanding();
        global.searchUp = 12;
        global.searchDown = 24;
        global.searchRadius = 5;
        TeleportConfig.SafeLanding window = Elevator.window(global);
        assertEquals(2, window.searchUp);
        assertEquals(2, window.searchDown);
        assertEquals(0, window.searchRadius);
        assertFalse(window.allowWater);
        assertEquals(global.causes, window.causes);
    }
}
