package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import org.junit.jupiter.api.Test;

class NpcRayTest {
    private static final Set<String> KNOWN = Set.of("steve", "alex");

    @Test void exactHitWinsEvenWhenTheConeCandidateIsCloser() {
        NpcRay.Hit exact = new NpcRay.Hit(6, "steve");
        NpcRay.Hit cone = new NpcRay.Hit(1, "alex");
        assertEquals("steve", NpcRay.pick(exact, cone, 8, KNOWN::contains));
    }

    @Test void coneIsUsedWhenTheRayHitsNothing() {
        assertEquals("alex", NpcRay.pick(null, new NpcRay.Hit(4, "alex"), 8, KNOWN::contains));
    }

    @Test void staleMarkerFallsBackToTheCone() {
        NpcRay.Hit exact = new NpcRay.Hit(2, "deleted");
        NpcRay.Hit cone = new NpcRay.Hit(5, "alex");
        assertEquals("alex", NpcRay.pick(exact, cone, 8, KNOWN::contains));
    }

    @Test void hitOutsideTheRangeIsRejected() {
        assertNull(NpcRay.pick(new NpcRay.Hit(9, "steve"), new NpcRay.Hit(20, "alex"), 8, KNOWN::contains));
    }

    @Test void invalidDistancesAreRejected() {
        assertNull(NpcRay.pick(new NpcRay.Hit(Double.NaN, "steve"), null, 8, KNOWN::contains));
        assertNull(NpcRay.pick(new NpcRay.Hit(-1, "steve"), null, 8, KNOWN::contains));
    }

    @Test void hitWithoutAKeyIsIgnored() {
        assertNull(NpcRay.pick(new NpcRay.Hit(1, null), new NpcRay.Hit(2, null), 8, KNOWN::contains));
    }

    @Test void nothingToSelect() {
        assertNull(NpcRay.pick(null, null, 8, KNOWN::contains));
    }
}
