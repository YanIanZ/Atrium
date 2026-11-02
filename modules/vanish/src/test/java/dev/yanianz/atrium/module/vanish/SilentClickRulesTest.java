package dev.yanianz.atrium.module.vanish;

import static dev.yanianz.atrium.module.vanish.SilentClickRules.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

class SilentClickRulesTest {
    @Test void readOnlyViewCancelsEveryTopClick() {
        for (ClickType click : ClickType.values()) {
            assertEquals(CANCEL, SilentClickRules.decide(true, false, false, click, InventoryAction.PICKUP_ALL), click.name());
        }
    }

    @Test void modifyViewMapsTopClicksToLiveEdits() {
        assertEquals(SWAP_WITH_CURSOR, SilentClickRules.decide(true, false, true, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        assertEquals(TAKE_TO_PLAYER, SilentClickRules.decide(true, false, true, ClickType.SHIFT_LEFT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertEquals(CANCEL, SilentClickRules.decide(true, false, true, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP));
        assertEquals(CANCEL, SilentClickRules.decide(true, false, true, ClickType.MIDDLE, InventoryAction.CLONE_STACK));
        assertEquals(CANCEL, SilentClickRules.decide(true, false, true, ClickType.DOUBLE_CLICK,
                InventoryAction.COLLECT_TO_CURSOR));
    }

    @Test void bottomClicksNeverMoveItemsIntoTheCopy() {
        assertEquals(ALLOW, SilentClickRules.decide(false, true, false, ClickType.LEFT, InventoryAction.PICKUP_ALL));
        assertEquals(CANCEL, SilentClickRules.decide(false, true, false, ClickType.SHIFT_LEFT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertEquals(PUT_INTO_CONTAINER, SilentClickRules.decide(false, true, true, ClickType.SHIFT_LEFT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY));
        assertEquals(CANCEL, SilentClickRules.decide(false, true, true, ClickType.SHIFT_RIGHT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY));
        // Double-click would collect copies from the view.
        assertEquals(CANCEL, SilentClickRules.decide(false, true, true, ClickType.DOUBLE_CLICK,
                InventoryAction.COLLECT_TO_CURSOR));
        assertEquals(CANCEL, SilentClickRules.decide(false, true, true, ClickType.UNKNOWN, InventoryAction.UNKNOWN));
    }

    @Test void outsideClicksOnlyTouchTheCursor() {
        assertEquals(ALLOW, SilentClickRules.decide(false, false, false, ClickType.LEFT, InventoryAction.DROP_ALL_CURSOR));
        assertEquals(CANCEL, SilentClickRules.decide(false, false, false, ClickType.DOUBLE_CLICK,
                InventoryAction.COLLECT_TO_CURSOR));
    }
}
