package dev.yanianz.atrium.core.menu;

import dev.yanianz.atrium.core.message.Theme;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Opens dupe-safe chest menus ({@code ctx.menus()}). Methods may be called from any thread; the work runs
 * on the viewer's own thread (inline when already there). Menus opened through a module's service are
 * closed automatically when that module is disabled; all menus close on quit, world change and plugin
 * disable.
 */
public interface MenuService {
    /** Opens a menu as a new navigation root (clears the back history). */
    void open(Player viewer, MenuProvider menu);

    /** Opens a child menu; {@link #back(Player)} returns to the current one. */
    void push(Player viewer, MenuProvider menu);

    /** Replaces the current menu without a history entry (page flips). Updates in place when the size and title are unchanged. */
    void replace(Player viewer, MenuProvider menu);

    /** Re-renders the viewer's current menu from its provider (in place when possible). */
    void refresh(Player viewer);

    /** Goes back one menu; closes when there is no history. Returns whether a previous menu existed. */
    boolean back(Player viewer);

    void close(Player viewer);

    /** Whether the viewer currently looks at an Atrium menu. */
    boolean isViewing(Player viewer);

    /** Whether {@link #back(Player)} would open a previous menu. Accurate on the viewer's thread. */
    boolean hasHistory(Player viewer);

    /**
     * A yes/no dialog. {@code onConfirm}/{@code onCancel} run on the viewer's thread; afterwards the dialog
     * returns to the previous menu (or closes) unless the handler opened another menu.
     */
    void confirm(Player viewer, Component title, List<Component> details, ClickHandler onConfirm,
            @Nullable ClickHandler onCancel);

    /** A translated line for the viewer ({@code Messages.render}), e.g. for icon names. */
    Component text(Player viewer, String key, TagResolver... resolvers);

    /** The theme filler item (empty slots), or an air stack when fillers are disabled. */
    ItemStack filler();

    void playClick(Player viewer);

    void playError(Player viewer);

    Theme theme();

    /** True if the inventory belongs to an Atrium menu. */
    static boolean isMenu(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder(false) instanceof MenuHolder;
    }
}
