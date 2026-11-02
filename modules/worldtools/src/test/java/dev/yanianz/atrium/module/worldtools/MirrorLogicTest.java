package dev.yanianz.atrium.module.worldtools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Radius, rate limit and item consumption decisions of mirror building. */
class MirrorLogicTest {
    @Test void targetIsTheTransformedPositionAroundTheCentre() {
        assertEquals(Optional.of(new MirrorTargets.Target(8, 64, 13)),
                MirrorTargets.target(MirrorTransform.MIRROR_X, 10, 64, 10, 12, 64, 13, 32));
        assertEquals(Optional.of(new MirrorTargets.Target(10, 64, 7)),
                MirrorTargets.target(MirrorTransform.MIRROR_Z, 10, 64, 10, 10, 64, 13, 32));
        assertEquals(Optional.of(new MirrorTargets.Target(9, 64, 12)),
                MirrorTargets.target(MirrorTransform.ROTATE_90, 10, 64, 10, 12, 64, 11, 32));
        assertEquals(Optional.of(new MirrorTargets.Target(13, 65, 10)),
                MirrorTargets.target(MirrorTransform.DIAGONAL, 10, 64, 10, 10, 65, 13, 32));
    }

    @Test void theCentreBlockNeverCopiesItself() {
        for (MirrorTransform transform : MirrorTransform.all()) {
            assertTrue(MirrorTargets.target(transform, 5, 70, -3, 5, 70, -3, 32).isEmpty(),
                    transform + " must not duplicate the centre block");
        }
    }

    @Test void targetsOutsideTheRadiusAreSkipped() {
        // Radius counts from the centre to the target, so a block mirrored just over the edge is skipped.
        assertTrue(MirrorTargets.target(MirrorTransform.MIRROR_X, 0, 64, 0, 5, 64, 0, 4).isEmpty());
        assertTrue(MirrorTargets.target(MirrorTransform.MIRROR_X, 0, 64, 0, 4, 64, 0, 4).isPresent());
        assertTrue(MirrorTargets.target(MirrorTransform.MIRROR_X, 0, 64, 0, 8, 64, 0, 8).isPresent());
        assertTrue(MirrorTargets.target(MirrorTransform.MIRROR_X, 0, 64, 0, 9, 64, 0, 8).isEmpty());
        // Vertical distance also counts, and a radius below 1 is treated as 1.
        assertTrue(MirrorTargets.target(MirrorTransform.MIRROR_Y, 0, 64, 0, 0, 70, 0, 4).isEmpty());
        assertTrue(MirrorTargets.target(MirrorTransform.MIRROR_X, 0, 64, 0, 1, 64, 0, 0).isPresent());
        assertTrue(MirrorTargets.target(MirrorTransform.MIRROR_X, 0, 64, 0, 2, 64, 0, 0).isEmpty());
    }

    @Test void outsideUsesChebyshevDistance() {
        assertFalse(MirrorTargets.outside(3, 64, 3, 0, 64, 0, 3));
        assertFalse(MirrorTargets.outside(3, 67, -3, 0, 64, 0, 3));
        assertTrue(MirrorTargets.outside(4, 64, 0, 0, 64, 0, 3));
        assertTrue(MirrorTargets.outside(0, 61, 0, 0, 64, 0, 2));
    }

    @Test void rateLimiterBurstsThenRefills() {
        MirrorRateLimiter limiter = new MirrorRateLimiter();
        assertTrue(limiter.tryAcquire(0L, 1, 2, 2));
        assertTrue(limiter.tryAcquire(0L, 1, 2, 2));
        assertFalse(limiter.tryAcquire(0L, 1, 2, 2), "burst exhausted");
        // Two operations per second: one token every 500 ms.
        assertFalse(limiter.tryAcquire(499_999_999L, 1, 2, 2));
        assertTrue(limiter.tryAcquire(500_000_000L, 1, 2, 2));
        assertFalse(limiter.tryAcquire(500_000_000L, 1, 2, 2));
        assertTrue(limiter.tryAcquire(2_000_000_000L, 1, 2, 2));
        // Credit never exceeds the burst: after a long pause only two operations fit.
        assertTrue(limiter.tryAcquire(60_000_000_000L, 1, 2, 2));
        assertTrue(limiter.tryAcquire(60_000_000_000L, 1, 2, 2));
        assertFalse(limiter.tryAcquire(60_000_000_000L, 1, 2, 2));
    }

    @Test void rateLimitIsAtomicForSeveralOperations() {
        MirrorRateLimiter limiter = new MirrorRateLimiter();
        assertFalse(limiter.tryAcquire(0L, 3, 2, 2), "three copies need three tokens");
        assertTrue(limiter.tryAcquire(0L, 1, 2, 2), "a refused multi-token request consumes nothing");
        assertTrue(limiter.tryAcquire(0L, 1, 2, 2));
        assertFalse(limiter.tryAcquire(0L, 1, 2, 2));
        assertTrue(limiter.tryAcquire(1_000_000_000L, 5, 100, 8));
    }

    @Test void oneOperationPerSecondStillWorks() {
        MirrorRateLimiter limiter = new MirrorRateLimiter();
        assertTrue(limiter.tryAcquire(10L, 1, 1, 1));
        assertFalse(limiter.tryAcquire(500_000_000L, 1, 1, 1));
        assertTrue(limiter.tryAcquire(1_000_000_010L, 1, 1, 1));
    }

    @Test void survivalPaysOneItemPerCopyAllOrNothing() {
        assertEquals(1, MirrorCost.itemsToConsume(false, false, true, false, 1, 1));
        assertEquals(3, MirrorCost.itemsToConsume(false, false, true, false, 3, 5));
        assertEquals(3, MirrorCost.itemsToConsume(false, false, true, false, 3, 3));
        assertEquals(MirrorCost.NOT_ENOUGH, MirrorCost.itemsToConsume(false, false, true, false, 1, 0));
        assertEquals(MirrorCost.NOT_ENOUGH, MirrorCost.itemsToConsume(false, false, true, false, 3, 2));
    }

    @Test void creativeAndBypassAreFree() {
        assertEquals(0, MirrorCost.itemsToConsume(true, false, true, false, 3, 0), "creative is free by default");
        assertEquals(MirrorCost.NOT_ENOUGH, MirrorCost.itemsToConsume(true, false, true, true, 1, 0),
                "charging creative needs the items");
        assertEquals(1, MirrorCost.itemsToConsume(true, false, true, true, 1, 1));
        assertEquals(0, MirrorCost.itemsToConsume(false, true, true, false, 9, 0), "bypass permission is free");
        assertEquals(0, MirrorCost.itemsToConsume(false, false, false, false, 9, 0),
                "mirrorChargeSurvival=false makes survival free");
        assertEquals(0, MirrorCost.itemsToConsume(true, true, false, true, 9, 0), "everything off is free");
        assertEquals(0, MirrorCost.itemsToConsume(false, false, true, false, 0, 0), "no copies cost nothing");
        assertEquals(0, MirrorCost.itemsToConsume(false, false, true, false, -4, 0));
    }
}
