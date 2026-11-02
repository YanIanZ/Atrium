package dev.yanianz.atrium.core.menu;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.event.inventory.InventoryAction;

/**
 * Decides which inventory interactions a menu lets through. Everything is denied unless it only moves
 * items between the cursor and a slot the menu declared as an input slot. Pure, so it is unit-tested.
 *
 * <p>Always denied: shift-click from the player inventory (it would fill the first free menu slot),
 * double-click collect (it gathers from every slot), clone, drops, unknown actions, and every click on a
 * menu slot that is not an input slot.
 */
public final class ClickPolicy {
    /** Actions that only exchange items between the cursor (or a hotbar slot) and the clicked slot. */
    private static final Set<InventoryAction> SLOT_LOCAL = EnumSet.of(
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_SOME,
            InventoryAction.PICKUP_ONE, InventoryAction.PLACE_ALL, InventoryAction.PLACE_SOME,
            InventoryAction.PLACE_ONE, InventoryAction.SWAP_WITH_CURSOR, InventoryAction.HOTBAR_SWAP);

    private ClickPolicy() {}

    /**
     * @param rawSlot raw slot of the view (menu slots are {@code 0..topSize-1}, the player inventory follows;
     *     negative = outside the window)
     * @param topSize number of menu slots
     * @param inputSlots menu slots declared as input
     * @param playerInventoryInput whether simple clicks inside the player's own inventory are allowed
     */
    public static boolean allowsClick(int rawSlot, int topSize, InventoryAction action, Set<Integer> inputSlots,
            boolean playerInventoryInput) {
        if (action == null || rawSlot < 0) {
            return false;
        }
        if (rawSlot < topSize) {
            if (!inputSlots.contains(rawSlot)) {
                return false;
            }
            // Shift-click out of an input slot only moves into the player's inventory: safe.
            return SLOT_LOCAL.contains(action) || action == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        }
        if (!playerInventoryInput || inputSlots.isEmpty()) {
            return false;
        }
        // Inside the player's inventory: no shift-click (would move into arbitrary menu slots).
        return SLOT_LOCAL.contains(action);
    }

    /** A drag is allowed only if every touched slot is an input slot or (if enabled) a player-inventory slot. */
    public static boolean allowsDrag(Set<Integer> rawSlots, int topSize, Set<Integer> inputSlots,
            boolean playerInventoryInput) {
        if (rawSlots.isEmpty() || inputSlots.isEmpty()) {
            return false;
        }
        for (int raw : rawSlots) {
            if (raw < 0) {
                return false;
            }
            if (raw < topSize ? !inputSlots.contains(raw) : !playerInventoryInput) {
                return false;
            }
        }
        return true;
    }
}
