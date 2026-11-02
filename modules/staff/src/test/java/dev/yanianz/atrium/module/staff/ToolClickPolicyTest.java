package dev.yanianz.atrium.module.staff;

import static dev.yanianz.atrium.module.staff.ToolClickPolicy.Category.DROP;
import static dev.yanianz.atrium.module.staff.ToolClickPolicy.Category.MOVE;
import static dev.yanianz.atrium.module.staff.ToolClickPolicy.Category.OTHER;
import static dev.yanianz.atrium.module.staff.ToolClickPolicy.Category.SHIFT;
import static dev.yanianz.atrium.module.staff.ToolClickPolicy.Kind.EMPTY;
import static dev.yanianz.atrium.module.staff.ToolClickPolicy.Kind.TOOL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ToolClickPolicyTest {
    private static final ToolClickPolicy.Kind ITEM = ToolClickPolicy.Kind.OTHER;

    @Test
    void clicksWithoutToolsAreNeverTouched() {
        assertTrue(ToolClickPolicy.allowed(SHIFT, false, true, ITEM, ITEM, EMPTY));
        assertTrue(ToolClickPolicy.allowed(DROP, false, false, ITEM, EMPTY, EMPTY));
    }

    @Test
    void toolsMoveFreelyInsideOwnStorage() {
        assertTrue(ToolClickPolicy.allowed(MOVE, true, false, EMPTY, TOOL, EMPTY));
        assertTrue(ToolClickPolicy.allowed(MOVE, true, false, TOOL, EMPTY, EMPTY));
        assertTrue(ToolClickPolicy.allowed(MOVE, true, false, TOOL, TOOL, EMPTY));
        assertTrue(ToolClickPolicy.allowed(MOVE, true, false, EMPTY, EMPTY, TOOL)); // number key, hotbar
    }

    @Test
    void containersAndCraftingGridAreRefused() {
        assertFalse(ToolClickPolicy.allowed(MOVE, false, true, TOOL, EMPTY, EMPTY)); // chest slot
        assertFalse(ToolClickPolicy.allowed(MOVE, false, false, TOOL, EMPTY, EMPTY)); // 2x2 grid / armour
        assertFalse(ToolClickPolicy.allowed(MOVE, true, true, TOOL, EMPTY, EMPTY)); // own slot, chest open
        assertFalse(ToolClickPolicy.allowed(MOVE, false, true, EMPTY, EMPTY, TOOL)); // number key into chest
    }

    @Test
    void bundlesAndOtherItemsNeverMeetATool() {
        assertFalse(ToolClickPolicy.allowed(MOVE, true, false, ITEM, TOOL, EMPTY)); // bundle on cursor
        assertFalse(ToolClickPolicy.allowed(MOVE, true, false, TOOL, ITEM, EMPTY)); // tool onto bundle
        assertFalse(ToolClickPolicy.allowed(MOVE, true, false, EMPTY, ITEM, TOOL)); // swap with an item
    }

    @Test
    void shiftDropCollectAndCreativeCloneAreRefused() {
        assertFalse(ToolClickPolicy.allowed(SHIFT, true, false, EMPTY, TOOL, EMPTY));
        assertFalse(ToolClickPolicy.allowed(DROP, true, false, EMPTY, TOOL, EMPTY));
        assertFalse(ToolClickPolicy.allowed(OTHER, true, false, TOOL, TOOL, EMPTY));
    }
}
