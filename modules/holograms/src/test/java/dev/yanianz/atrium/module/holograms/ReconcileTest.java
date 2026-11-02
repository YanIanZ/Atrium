package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReconcileTest {
    private static final StoredLocation HERE = new StoredLocation("world", 1, 2, 3, 0, 0);
    private static final StoredLocation THERE = new StoredLocation("world", 4, 5, 6, 0, 0);

    @Test void plansCreateUpdateAndOrphanDelete() {
        Reconcile.Plan plan = Reconcile.plan(
                List.of(new Reconcile.Desired("atrium_a", "same"), new Reconcile.Desired("atrium_b", "new"),
                        new Reconcile.Desired("atrium_c", "new")),
                List.of(new Reconcile.Existing("atrium_a", "same"), new Reconcile.Existing("atrium_b", "old"),
                        new Reconcile.Existing("atrium_orphan", "old")));
        assertEquals(List.of("atrium_c"), plan.create());
        assertEquals(List.of("atrium_b"), plan.update());
        assertEquals(List.of("atrium_orphan"), plan.delete());
        assertEquals(3, plan.total());
    }

    @Test void neverTouchesUnmanagedHolograms() {
        Reconcile.Plan plan = Reconcile.plan(
                List.of(new Reconcile.Desired("atrium_mine", "x")),
                List.of(new Reconcile.Existing("shop", "whatever"), new Reconcile.Existing("atrium_mine", "x")));
        assertTrue(plan.isEmpty());
    }

    @Test void identicalSetsProduceNoWork() {
        Reconcile.Plan plan = Reconcile.plan(
                List.of(new Reconcile.Desired("atrium_a", "x"), new Reconcile.Desired("atrium_b", "y")),
                List.of(new Reconcile.Existing("atrium_b", "y"), new Reconcile.Existing("atrium_a", "x")));
        assertTrue(plan.isEmpty());
        assertTrue(Reconcile.plan(List.of(), List.of()).isEmpty());
    }

    @Test void ignoresDesiredNamesThatDoNotCarryTheManagedPrefix() {
        Reconcile.Plan plan = Reconcile.plan(
                List.of(new Reconcile.Desired("foreign", "x")),
                List.of(new Reconcile.Existing("atrium_old", "x")));
        assertTrue(plan.create().isEmpty());
    }

    @Test void fingerprintChangesWithLocationPagesAndLines() {
        String base = Reconcile.fingerprint(HERE, List.of(List.of("a", "b")));
        String same = Reconcile.fingerprint(new StoredLocation("world", 1, 2, 3, 0, 0), List.of(List.of("a", "b")));
        assertEquals(base, same);
        assertNotEquals(base, Reconcile.fingerprint(THERE, List.of(List.of("a", "b"))));
        assertNotEquals(base, Reconcile.fingerprint(HERE, List.of(List.of("a", "c"))));
        assertNotEquals(base, Reconcile.fingerprint(HERE, List.of(List.of("a"), List.of("b"))));
        assertNotEquals(base, Reconcile.fingerprint(HERE, List.of(List.of("ab"))));
    }
}
