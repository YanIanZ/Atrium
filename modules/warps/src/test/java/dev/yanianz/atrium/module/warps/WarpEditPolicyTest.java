package dev.yanianz.atrium.module.warps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WarpEditPolicyTest {
    @Test
    void editPermissionAloneChangesNothing() {
        // Neither the admin permission nor ownership: refused, whatever the action.
        assertFalse(WarpEditPolicy.mayManage(false, false, false));
        assertFalse(WarpEditPolicy.mayManage(false, true, false));
        assertFalse(WarpEditPolicy.mayManage(false, false, true));
    }

    @Test
    void staffAndOwnersMayManage() {
        assertTrue(WarpEditPolicy.mayManage(true, false, false));
        assertTrue(WarpEditPolicy.mayManage(false, true, true));
    }

    @Test
    void deleteNeedsTheDeletePermission() {
        assertEquals(WarpsModule.PERM_DELWARP, WarpEditPolicy.Action.DELETE.adminPermission());
        for (WarpEditPolicy.Action action : WarpEditPolicy.Action.values()) {
            if (action != WarpEditPolicy.Action.DELETE) {
                assertEquals(WarpsModule.PERM_SETWARP, action.adminPermission(), action.name());
            }
        }
    }
}
