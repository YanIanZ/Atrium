package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackHistoryTest {
    private static BackHistory.Entry entry(int x, long at) {
        return new BackHistory.Entry(new StoredLocation("world", x, 64, 0, 0f, 0f), false, at);
    }

    @Test
    void newestFirstAndOldestDroppedWhenFull() {
        BackHistory history = BackHistory.EMPTY;
        for (int i = 1; i <= 5; i++) {
            history = history.push(entry(i * 10, i), 3);
        }
        assertEquals(3, history.size());
        assertEquals(50, history.get(1).orElseThrow().location().x());
        assertEquals(40, history.get(2).orElseThrow().location().x());
        assertEquals(30, history.get(3).orElseThrow().location().x());
        assertTrue(history.get(4).isEmpty());
        assertTrue(history.get(0).isEmpty());
    }

    @Test
    void sameSpotTwiceIsNotDuplicated() {
        BackHistory history = BackHistory.EMPTY.push(entry(1, 1), 5).push(entry(1, 2), 5);
        assertEquals(1, history.size());
        assertEquals(2, history.latest().orElseThrow().recordedAt());
    }

    @Test
    void capacityIsClamped() {
        BackHistory history = BackHistory.EMPTY;
        for (int i = 0; i < 100; i++) {
            history = history.push(entry(i, i), 1000);
        }
        assertEquals(BackHistory.MAX_CAPACITY, history.size());
        assertEquals(1, BackHistory.EMPTY.push(entry(1, 1), 0).push(entry(2, 2), 0).size());
    }

    @Test
    void withoutTrimAndExpiry() {
        BackHistory history = new BackHistory(List.of(entry(3, 300), entry(2, 200), entry(1, 100)));
        assertEquals(List.of(entry(3, 300), entry(1, 100)), history.without(entry(2, 200)).entries());
        assertSame(history, history.without(entry(9, 9)));
        assertEquals(2, history.trim(2).size());
        assertEquals(List.of(entry(3, 300), entry(2, 200)), history.recordedSince(200).entries());
    }

    @Test
    void encodeRoundTripKeepsOrderAndDeathFlag() {
        BackHistory history = new BackHistory(List.of(
                new BackHistory.Entry(new StoredLocation("world;odd", 1.5, -60, 2.25, 90f, 10f), true, 123L),
                entry(7, 45L)));
        assertEquals(history, BackHistory.decode(history.encode()));
        assertEquals(BackHistory.EMPTY, BackHistory.decode(""));
        assertEquals(BackHistory.EMPTY, BackHistory.decode(null));
        assertEquals(1, BackHistory.decode("garbage\n12;0;world;1;2;3;0;0").size());
    }
}
