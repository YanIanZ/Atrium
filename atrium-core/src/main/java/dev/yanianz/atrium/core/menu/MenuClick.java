package dev.yanianz.atrium.core.menu;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;

/** What a {@link ClickHandler} gets: who clicked, how, and navigation shortcuts. */
public final class MenuClick {
    private final Player player;
    private final ClickType type;
    private final int slot;
    private final MenuHolder holder;
    private final MenuService menus;

    MenuClick(Player player, ClickType type, int slot, MenuHolder holder, MenuService menus) {
        this.player = player;
        this.type = type;
        this.slot = slot;
        this.holder = holder;
        this.menus = menus;
    }

    public Player player() {
        return player;
    }

    public ClickType type() {
        return type;
    }

    public boolean isLeft() {
        return type.isLeftClick();
    }

    public boolean isRight() {
        return type.isRightClick();
    }

    public boolean isShift() {
        return type.isShiftClick();
    }

    public int slot() {
        return slot;
    }

    public Menu menu() {
        return holder.menu();
    }

    public Inventory inventory() {
        return holder.getInventory();
    }

    public MenuService menus() {
        return menus;
    }

    /** Opens a child menu; {@link #back()} from there returns here. */
    public void open(MenuProvider next) {
        menus.push(player, next);
    }

    /** Replaces this menu without adding a history entry (page flips, tabs). */
    public void replace(MenuProvider next) {
        menus.replace(player, next);
    }

    /** Re-renders the current menu from its provider. */
    public void refresh() {
        menus.refresh(player);
    }

    /** Returns to the previous menu, or closes if there is none. */
    public void back() {
        menus.back(player);
    }

    public void close() {
        menus.close(player);
    }

    /** Plays the theme error sound (use when the action was refused). */
    public void error() {
        menus.playError(player);
    }

    /** True while this click's menu is still the viewer's open menu. */
    public boolean isStillOpen() {
        return !holder.isClosed() && MenuHolder.of(player.getOpenInventory()) == holder;
    }
}
