package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemCooldownTrackerTest {
    @Test
    void aStartedCooldownBlocksUntilItExpires() {
        ItemCooldownTracker tracker = new ItemCooldownTracker();
        UUID player = UUID.randomUUID();
        assertTrue(tracker.ready(player, "rocket", 0));
        tracker.start(player, "rocket", 0, 30);
        assertFalse(tracker.ready(player, "rocket", 29_000));
        assertEquals(1, tracker.remainingSeconds(player, "rocket", 29_500));
        assertEquals(30, tracker.remainingSeconds(player, "rocket", 0));
        assertTrue(tracker.ready(player, "rocket", 30_000));
        assertEquals(0, tracker.remainingSeconds(player, "rocket", 30_000));
    }

    @Test
    void idsAndPlayersAreIndependentAndForgetClearsEverything() {
        ItemCooldownTracker tracker = new ItemCooldownTracker();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        tracker.start(a, "one", 0, 10);
        tracker.start(a, "two", 0, 10);
        tracker.start(b, "one", 0, 10);
        assertEquals(2, tracker.tracked(a));
        assertFalse(tracker.ready(a, "one", 5));
        assertFalse(tracker.ready(a, "two", 5));
        assertFalse(tracker.ready(b, "one", 5));
        tracker.forget(a);
        assertEquals(0, tracker.tracked(a));
        assertTrue(tracker.ready(a, "one", 5));
        assertFalse(tracker.ready(b, "one", 5), "forgetting one player keeps the other");
    }

    @Test
    void aNonPositiveStartClearsTheCooldown() {
        ItemCooldownTracker tracker = new ItemCooldownTracker();
        UUID player = UUID.randomUUID();
        tracker.start(player, "x", 0, 10);
        tracker.start(player, "x", 1_000, 0);
        assertTrue(tracker.ready(player, "x", 1_000));
        assertEquals(0, tracker.tracked(player));
        tracker.start(null, "x", 0, 10);
        tracker.start(player, null, 0, 10);
    }
}
