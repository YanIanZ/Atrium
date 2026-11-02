package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class PendingActionsTest {
    @Test
    void secondCallInsideTheWindowConfirmsOnce() {
        AtomicLong now = new AtomicLong(1_000L);
        PendingActions pending = new PendingActions(now::get);
        pending.request("key", 30_000L);
        now.addAndGet(5_000L);
        assertTrue(pending.confirm("key"));
        assertFalse(pending.confirm("key"));
        assertEquals(0, pending.size());
    }

    @Test
    void expiredRequestIsRejectedAndDropped() {
        AtomicLong now = new AtomicLong(1_000L);
        PendingActions pending = new PendingActions(now::get);
        pending.request("key", 1_000L);
        now.addAndGet(1_001L);
        assertFalse(pending.confirm("key"));
        assertEquals(0, pending.size());
    }

    @Test
    void keysAreIndependent() {
        PendingActions pending = new PendingActions(() -> 0L);
        pending.request("a", 10_000L);
        pending.request("b", 10_000L);
        assertTrue(pending.confirm("a"));
        assertFalse(pending.confirm("a"));
        assertTrue(pending.confirm("b"));
    }
}
