package dev.yanianz.atrium.module.inventories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.inventories.ClearAllPolicy.Kind;
import dev.yanianz.atrium.module.inventories.ClearAllPolicy.Refusal;
import org.junit.jupiter.api.Test;

/** Rules of /clearall: who may be targeted, who must confirm, and when -s applies. */
class ClearAllPolicyTest {
    @Test
    void selfTargetNeedsNoConfirmation() {
        assertEquals(Kind.SELF, ClearAllPolicy.kind(true, false));
        assertFalse(ClearAllPolicy.needsConfirmation(Kind.SELF));
        assertEquals(Refusal.NONE, ClearAllPolicy.check(Kind.SELF, false, false, true));
    }

    @Test
    void otherTargetNeedsPermissionAndConfirmation() {
        assertEquals(Kind.OTHER, ClearAllPolicy.kind(false, false));
        assertTrue(ClearAllPolicy.needsConfirmation(Kind.OTHER));
        assertEquals(Refusal.OTHERS_PERMISSION, ClearAllPolicy.check(Kind.OTHER, false, true, true));
        assertEquals(Refusal.NONE, ClearAllPolicy.check(Kind.OTHER, true, false, true));
    }

    @Test
    void everyoneNeedsTheBulkPermissionAndConfirmation() {
        assertEquals(Kind.ALL, ClearAllPolicy.kind(false, true));
        assertTrue(ClearAllPolicy.needsConfirmation(Kind.ALL));
        assertEquals(Refusal.ALL_PERMISSION, ClearAllPolicy.check(Kind.ALL, true, false, true));
        assertEquals(Refusal.NONE, ClearAllPolicy.check(Kind.ALL, false, true, true));
    }

    @Test
    void offlineOthersAreRefusedButPermissionIsCheckedFirst() {
        assertEquals(Refusal.OFFLINE, ClearAllPolicy.check(Kind.OTHER, true, true, false));
        // Never leak whether the target exists to a sender without the permission.
        assertEquals(Refusal.OTHERS_PERMISSION, ClearAllPolicy.check(Kind.OTHER, false, false, false));
    }

    @Test
    void silentOnlyWithPermission() {
        assertTrue(ClearAllPolicy.silent(true, true));
        assertFalse(ClearAllPolicy.silent(true, false));
        assertFalse(ClearAllPolicy.silent(false, true));
    }
}
