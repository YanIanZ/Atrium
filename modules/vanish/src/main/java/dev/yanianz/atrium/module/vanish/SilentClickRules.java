package dev.yanianz.atrium.module.vanish;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;

/**
 * Decides what a click in a silent container view does. The view only shows a copy of the container, so
 * vanilla must never move an item into or out of it: every such click is cancelled and, with the modify
 * permission, replaced by an explicit edit of the real container.
 */
final class SilentClickRules {
    enum Decision {
        /** Let vanilla handle it (the click only touches the player's own inventory or cursor). */
        ALLOW,
        /** Cancel without doing anything. */
        CANCEL,
        /** Exchange the cursor with the clicked container slot. */
        SWAP_WITH_CURSOR,
        /** Move the clicked container slot into the player's inventory. */
        TAKE_TO_PLAYER,
        /** Move the clicked player slot into the container. */
        PUT_INTO_CONTAINER
    }

    private SilentClickRules() {}

    static Decision decide(boolean topClicked, boolean bottomClicked, boolean modify, ClickType click,
            InventoryAction action) {
        if (topClicked) {
            if (!modify) {
                return Decision.CANCEL;
            }
            return switch (click) {
                case LEFT -> Decision.SWAP_WITH_CURSOR;
                case SHIFT_LEFT -> Decision.TAKE_TO_PLAYER;
                default -> Decision.CANCEL;
            };
        }
        if (bottomClicked) {
            return switch (action) {
                case MOVE_TO_OTHER_INVENTORY ->
                        modify && click == ClickType.SHIFT_LEFT ? Decision.PUT_INTO_CONTAINER : Decision.CANCEL;
                // Double-click collecting would pull copies out of the view.
                case COLLECT_TO_CURSOR, UNKNOWN -> Decision.CANCEL;
                default -> Decision.ALLOW;
            };
        }
        // Outside the window: only the cursor (the player's own item) is involved.
        return action == InventoryAction.COLLECT_TO_CURSOR ? Decision.CANCEL : Decision.ALLOW;
    }
}
