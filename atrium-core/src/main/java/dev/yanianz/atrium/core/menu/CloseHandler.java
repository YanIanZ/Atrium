package dev.yanianz.atrium.core.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * Called once when a menu closes (also when another menu replaces it), on the viewer's thread. Items still
 * in input slots afterwards are given back to the viewer (or dropped at their feet), so a handler that
 * consumes inputs must clear those slots itself.
 */
@FunctionalInterface
public interface CloseHandler {
    void onClose(Player viewer, Inventory inventory);
}
