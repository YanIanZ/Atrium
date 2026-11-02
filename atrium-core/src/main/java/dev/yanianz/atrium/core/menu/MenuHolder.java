package dev.yanianz.atrium.core.menu;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

/**
 * Identifies an Atrium menu inventory. Menus are recognised by this holder, never by their title, so a
 * renamed chest can never be mistaken for a menu (and a menu never for a chest).
 */
public final class MenuHolder implements InventoryHolder {
    private final MenuManager manager;
    private volatile String owner;
    private volatile MenuProvider provider;
    private final UUID viewer;
    private final Inventory inventory;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Menu menu;

    MenuHolder(MenuManager manager, String owner, MenuProvider provider, Menu menu, UUID viewer) {
        this.manager = manager;
        this.owner = owner;
        this.provider = provider;
        this.menu = menu;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, menu.size(), menu.title());
    }

    /** The menu holder of a view's top inventory, or null if the view is not an Atrium menu. */
    public static @org.jspecify.annotations.Nullable MenuHolder of(InventoryView view) {
        return view.getTopInventory().getHolder(false) instanceof MenuHolder holder ? holder : null;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public Menu menu() {
        return menu;
    }

    /** In-place re-render (page flip, refresh): same inventory, new content. */
    void update(MenuProvider provider, String owner, Menu menu) {
        this.provider = provider;
        this.owner = owner;
        this.menu = menu;
    }

    /** Module id that opened the menu ("core" for the framework). */
    public String owner() {
        return owner;
    }

    public UUID viewer() {
        return viewer;
    }

    MenuProvider provider() {
        return provider;
    }

    MenuManager manager() {
        return manager;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Marks the holder closed; true only for the first call. */
    boolean markClosed() {
        return closed.compareAndSet(false, true);
    }
}
