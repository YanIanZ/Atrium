package dev.yanianz.atrium.module.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ReplayGuardTest {
    private static final long NOW = 1_700_000_000_000L;

    private static byte[] nonce(int value) {
        byte[] nonce = new byte[16];
        Arrays.fill(nonce, (byte) value);
        return nonce;
    }

    @Test
    void firstSeenIsAcceptedAndSecondIsRejected() {
        ReplayGuard guard = new ReplayGuard(16, 60_000);

        assertTrue(guard.accept(nonce(1), NOW, NOW));
        assertFalse(guard.accept(nonce(1), NOW, NOW + 1));
        assertFalse(guard.accept(nonce(1), NOW, NOW + 59_999));
        assertTrue(guard.accept(nonce(2), NOW + 1, NOW + 1));
    }

    @Test
    void nonceCanBeReusedAfterTheTtl() {
        ReplayGuard guard = new ReplayGuard(16, 60_000);
        assertTrue(guard.accept(nonce(1), NOW, NOW));
        assertFalse(guard.accept(nonce(1), NOW, NOW + 59_999));
        assertTrue(guard.accept(nonce(1), NOW, NOW + 60_000));
    }

    @Test
    void cacheStaysBoundedAndEvictsTheOldestEntry() {
        ReplayGuard guard = new ReplayGuard(2, 60_000);
        assertTrue(guard.accept(nonce(1), NOW, NOW));
        assertTrue(guard.accept(nonce(2), NOW + 1, NOW + 1));
        assertTrue(guard.accept(nonce(3), NOW + 2, NOW + 2));
        assertTrue(guard.size(NOW + 2) <= 2);
        // The oldest nonce was evicted, so its timestamp is now the watermark and replaying the exact
        // envelope (same nonce and same timestamp) is refused.
        assertFalse(guard.accept(nonce(1), NOW, NOW + 3));
        // A fresh envelope with the same nonce but a newer timestamp is not a replay.
        assertTrue(guard.accept(nonce(9), NOW + 2, NOW + 3));
        assertFalse(guard.accept(nonce(3), NOW + 2, NOW + 3));
    }

    @Test
    void evictionWatermarkRejectsOlderEnvelopes() {
        ReplayGuard guard = new ReplayGuard(2, 60_000);
        assertTrue(guard.accept(nonce(1), NOW, NOW));
        assertTrue(guard.accept(nonce(2), NOW + 10, NOW + 10));
        // Filling the cache evicts nonce 1 and raises the watermark to its timestamp (NOW).
        assertTrue(guard.accept(nonce(3), NOW + 20, NOW + 20));

        assertFalse(guard.accept(nonce(4), NOW - 5, NOW + 30), "older than the evicted nonce");
        assertFalse(guard.accept(nonce(4), NOW, NOW + 30), "same timestamp as the evicted nonce");
        assertTrue(guard.accept(nonce(4), NOW + 1, NOW + 30), "newer than the watermark");
    }

    @Test
    void ttlAlwaysCoversTwiceTheTrustWindow() {
        assertEquals(605_000L, ReplayGuard.effectiveTtlMillis(60_000L, 300_000L));
        assertEquals(120_000L, ReplayGuard.effectiveTtlMillis(120_000L, 30_000L));
        assertEquals(65_000L, ReplayGuard.effectiveTtlMillis(60_000L, 30_000L));
    }

    @Test
    void malformedNoncesAreRejected() {
        ReplayGuard guard = new ReplayGuard(4, 60_000);
        assertFalse(guard.accept(null, NOW, NOW));
        assertFalse(guard.accept(new byte[8], NOW, NOW));
    }
}
