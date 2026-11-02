package dev.yanianz.atrium.module.deathmessages;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeathSpamGuardTest {
    @Test
    void allowsOnlyTheConfiguredNumberOfMessagesPerWindow() {
        DeathSpamGuard guard = new DeathSpamGuard();
        assertTrue(guard.allow("a", 1_000, 10_000, 2));
        assertTrue(guard.allow("a", 1_100, 10_000, 2));
        assertFalse(guard.allow("a", 1_200, 10_000, 2));
        assertTrue(guard.allow("b", 1_200, 10_000, 2), "another key has its own window");
    }

    @Test
    void theWindowSlides() {
        DeathSpamGuard guard = new DeathSpamGuard();
        assertTrue(guard.allow("a", 0, 10_000, 1));
        assertFalse(guard.allow("a", 9_999, 10_000, 1));
        assertTrue(guard.allow("a", 10_000, 10_000, 1), "the oldest record left the window");
    }

    @Test
    void zeroWindowOrCountDisablesTheGuard() {
        DeathSpamGuard guard = new DeathSpamGuard();
        for (int i = 0; i < 100; i++) {
            assertTrue(guard.allow("a", i, 0, 1));
            assertTrue(guard.allow("b", i, 10_000, 0));
        }
        assertTrue(guard.allow(null, 0, 10_000, 1));
    }
}
