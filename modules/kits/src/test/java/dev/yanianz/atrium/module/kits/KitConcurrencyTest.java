package dev.yanianz.atrium.module.kits;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Regression: two admins editing one kit must not silently overwrite each other (lost diamonds). */
class KitConcurrencyTest {
    @Test
    void secondEditorOfAKitIsRefused() {
        KitEditLocks<String> locks = new KitEditLocks<>();
        assertEquals(Optional.empty(), locks.tryAcquire("starter", "A"));
        assertEquals(Optional.of("A"), locks.tryAcquire("starter", "B"));
        assertEquals(Optional.empty(), locks.tryAcquire("starter", "A"), "re-acquire by the holder is fine");
        assertEquals(Optional.empty(), locks.tryAcquire("vip", "B"), "other kits are independent");
    }

    @Test
    void onlyTheHolderReleases() {
        KitEditLocks<String> locks = new KitEditLocks<>();
        locks.tryAcquire("starter", "A");
        assertFalse(locks.release("starter", "B"));
        assertEquals(Optional.of("A"), locks.holder("starter"));
        assertTrue(locks.release("starter", "A"));
        assertEquals(Optional.empty(), locks.holder("starter"));
        assertEquals(Optional.empty(), locks.tryAcquire("starter", "B"));
    }

    @Test
    void staleVersionLosesTheRace() {
        // A and B both start from version 3. A's write bumps the row to 4; B's write still names 3 and affects 0 rows.
        long stored = 3;
        long a = 3;
        long b = 3;
        assertEquals(stored, a);
        stored = KitVersions.next(a);
        assertEquals(4, stored);
        assertNotEquals(stored, b, "B's update must not match the stored version");
        assertFalse(KitVersions.applied(0));
        assertTrue(KitVersions.applied(1));
    }

    @Test
    void versionWrapsInsteadOfOverflowing() {
        assertEquals(1, KitVersions.next(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> KitVersions.next(-1));
    }

    @Test
    void editsKeepTheVersionTheyStartedFrom() {
        Kit kit = new Kit("starter", 0, false, false, BigDecimal.ZERO, new byte[0], null, "", -1, 1).withVersion(7);
        assertEquals(7, kit.withCooldown(60, 2).version());
        assertEquals(7, kit.withCost(BigDecimal.TEN, 2).version());
        assertEquals(7, kit.withItems(new byte[] {1}, 2).version());
        assertEquals(0, new Kit("x", 0, false, false, BigDecimal.ZERO, new byte[0], null, "", -1, 1).version());
        assertThrows(IllegalArgumentException.class, () -> kit.withVersion(-1));
    }
}
