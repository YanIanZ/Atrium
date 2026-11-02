package dev.yanianz.atrium.module.chat;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RangeTableTest {
    @Test
    void emptyTableUsesTheFallback() {
        assertEquals(75, RangeTable.resolve(List.of(), 10, 75));
    }

    @Test
    void picksTheHighestMinimumThatTheOnlineCountReaches() {
        List<RangeTable.Step> steps = RangeTable.compile(List.of(step(30, 50), step(0, 200), step(10, 100)));

        assertEquals(200, RangeTable.resolve(steps, 5, 999));
        assertEquals(100, RangeTable.resolve(steps, 10, 999));
        assertEquals(50, RangeTable.resolve(steps, 40, 999));
    }

    @Test
    void usesTheFirstEntryWhenEverybodyIsBelowIt() {
        List<RangeTable.Step> steps = RangeTable.compile(List.of(step(50, 20), step(100, 10)));

        assertEquals(20, RangeTable.resolve(steps, 2, 999));
    }

    @Test
    void distanceIsCheckedInThreeDimensionsAndPerWorld() {
        RangeTable.Position origin = new RangeTable.Position("world", 0, 64, 0);

        assertTrue(RangeTable.inRange(origin, new RangeTable.Position("world", 30, 64, 40), 50));
        assertFalse(RangeTable.inRange(origin, new RangeTable.Position("world", 60, 64, 0), 50));
        assertFalse(RangeTable.inRange(origin, new RangeTable.Position("nether", 0, 64, 0), 50));
        assertTrue(RangeTable.inRange(null, null, 1));
    }

    private static ChatConfig.RangeStep step(int online, int range) {
        ChatConfig.RangeStep step = new ChatConfig.RangeStep();
        step.minOnline = online;
        step.range = range;
        return step;
    }
}
