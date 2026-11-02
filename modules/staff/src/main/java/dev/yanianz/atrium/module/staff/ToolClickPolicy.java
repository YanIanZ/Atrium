package dev.yanianz.atrium.module.staff;

/**
 * Decides whether an inventory click that involves a staff tool may go through. Pure, unit-tested.
 *
 * <p>A staff tool may only move between the 36 storage slots of the staff member's own inventory while no
 * other container is open, and only onto an empty slot or another tool. Everything else (containers,
 * crafting grid, armour/off-hand slots, bundles, shift-click, number-key swap into another inventory,
 * drop, double-click collect, creative clone) is refused.
 */
final class ToolClickPolicy {
    /** Item kind of each stack taking part in the click. */
    enum Kind {
        EMPTY,
        TOOL,
        OTHER
    }

    /** Coarse click category, mapped from Bukkit's click type and inventory action by the listener. */
    enum Category {
        /** Pick up, place, swap with cursor, number-key or off-hand swap inside the clicked inventory. */
        MOVE,
        /** Shift-click (move to the other inventory). */
        SHIFT,
        /** Any drop (Q, ctrl+Q, outside the window). */
        DROP,
        /** Double-click collect, creative clone, and anything unknown. */
        OTHER
    }

    private ToolClickPolicy() {
    }

    /**
     * @param category     what the click does
     * @param ownStorage   the clicked slot is one of the 36 storage slots of the clicker's own inventory
     * @param otherViewOpen a container other than the clicker's own inventory/crafting grid is open
     * @param involved     every stack taking part (cursor, clicked slot, hotbar/off-hand item for swaps)
     * @return whether the click may happen; always true when no staff tool takes part
     */
    static boolean allowed(Category category, boolean ownStorage, boolean otherViewOpen, Kind... involved) {
        boolean tool = false;
        boolean other = false;
        for (Kind kind : involved) {
            tool |= kind == Kind.TOOL;
            other |= kind == Kind.OTHER;
        }
        if (!tool) {
            return true;
        }
        if (category != Category.MOVE || other || !ownStorage || otherViewOpen) {
            return false;
        }
        return true;
    }
}
