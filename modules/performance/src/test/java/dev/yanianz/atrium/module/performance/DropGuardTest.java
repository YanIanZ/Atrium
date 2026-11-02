package dev.yanianz.atrium.module.performance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropGuardTest {
    private static final long WORLD = 0x5DEECE66DL;

    @Test
    void protectionCoversTheSpawnBurstAndThenExpires() {
        DropGuard guard = new DropGuard();
        long key = DropGuard.chunkKey(WORLD, 4, -7);
        long now = 1_000L;
        guard.protect(key, now);
        assertTrue(guard.isProtected(key, now));
        assertTrue(guard.isProtected(key, now + DropGuard.WINDOW_MILLIS));
        assertFalse(guard.isProtected(key, now + DropGuard.WINDOW_MILLIS + 1));
    }

    @Test
    void neighbouringChunksAndWorldsAreNotProtected() {
        DropGuard guard = new DropGuard();
        long key = DropGuard.chunkKey(WORLD, 4, -7);
        guard.protect(key, 0L);
        assertFalse(guard.isProtected(DropGuard.chunkKey(WORLD, 5, -7), 0L));
        assertFalse(guard.isProtected(DropGuard.chunkKey(WORLD, 4, -6), 0L));
        assertFalse(guard.isProtected(DropGuard.chunkKey(WORLD + 1, 4, -7), 0L));
        assertTrue(guard.isProtected(key, 0L));
    }

    @Test
    void pruneRemovesOnlyExpiredChunks() {
        DropGuard guard = new DropGuard();
        long stale = DropGuard.chunkKey(WORLD, 0, 0);
        long live = DropGuard.chunkKey(WORLD, 0, 1);
        guard.protect(stale, 0L);
        guard.protect(live, DropGuard.WINDOW_MILLIS);
        guard.prune(DropGuard.WINDOW_MILLIS + 1);
        assertFalse(guard.isProtected(stale, DropGuard.WINDOW_MILLIS + 1));
        assertTrue(guard.isProtected(live, DropGuard.WINDOW_MILLIS + 1));
    }
}
