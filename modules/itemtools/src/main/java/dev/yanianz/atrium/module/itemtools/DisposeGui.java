package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.module.ModuleContext;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

/**
 * The /dispose trash, an Atrium menu ({@code ctx.menus()}) whose slots are all input slots. It starts empty
 * and is never saved. By design everything still inside when it closes is deleted: the close handler clears
 * the input slots before the menu service would return them, so there is no path that gives an item back
 * and nothing can be duplicated. Shift-clicking from the player's inventory is refused by the menu service
 * (it would fill arbitrary slots); items are placed with normal clicks or drags.
 */
final class DisposeGui {
    static final int ROWS = 4;

    private final ModuleContext ctx;

    DisposeGui(ModuleContext ctx) {
        this.ctx = ctx;
    }

    /** Safe from any thread; the menu service opens it on the player's thread. */
    void open(Player player) {
        int[] slots = new int[ROWS * 9];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = i;
        }
        Menu menu = Menu.builder(ctx.messages().render(player, "itemtools.dispose-title"), ROWS)
                .input(slots)
                .playerInventoryInput(true)
                .onClose(DisposeGui::destroy)
                .build();
        ctx.menus().open(player, menu);
    }

    /** Deletes the contents (runs before the menu service returns leftover inputs, so nothing is returned). */
    private static void destroy(Player viewer, Inventory inventory) {
        inventory.clear();
    }
}
