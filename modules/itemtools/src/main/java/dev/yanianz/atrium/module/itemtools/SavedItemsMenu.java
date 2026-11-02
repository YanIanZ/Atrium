package dev.yanianz.atrium.module.itemtools;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.message.Msg;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code /saveditems list}: a paged menu of the library. Every icon is a copy of the stored stack, so the
 * menu never hands out an item by accident: only the click handler gives a fresh copy. Left click gives one
 * copy, shift-left ten, right click asks before deleting the entry.
 */
final class SavedItemsMenu {
    private static final int MENU_ROWS = 6;

    private final ItemSupport support;
    private final SavedItemsStore store;
    private final ConfigHandle<ItemToolsConfig> config;

    SavedItemsMenu(ItemSupport support, SavedItemsStore store, ConfigHandle<ItemToolsConfig> config) {
        this.support = support;
        this.store = store;
        this.config = config;
    }

    /** Loads the library on an I/O thread and opens the menu on the viewer's thread. */
    void open(Player viewer) {
        store.list().whenComplete((entries, error) -> {
            if (error != null) {
                support.failed(viewer, "Listing saved items", error);
                return;
            }
            support.ctx().scheduler().entity(viewer, () -> show(viewer, entries), null);
        });
    }

    private void show(Player viewer, List<SavedItemsStore.Entry> entries) {
        List<SavedItemsStore.Entry> snapshot = List.copyOf(entries);
        support.ctx().menus().open(viewer, PagedMenu.builder(support.ctx().menus(),
                        support.ctx().messages().render(viewer, "itemtools.saveditems-title"))
                .rows(MENU_ROWS)
                .content(player -> snapshot.stream().map(entry -> button(player, entry)).toList())
                .build());
    }

    private Button button(Player viewer, SavedItemsStore.Entry entry) {
        List<Component> lore = new ArrayList<>();
        lore.add(support.ctx().messages().render(viewer, "itemtools.saveditems-entry-lore",
                Msg.number("amount", entry.item() == null ? 0 : decodeAmount(entry))));
        lore.add(support.ctx().messages().render(viewer, "itemtools.saveditems-entry-click"));
        lore.add(support.ctx().messages().render(viewer, "itemtools.saveditems-entry-delete"));
        ItemStack icon = ItemBuilder.from(icon(entry))
                .name(support.ctx().messages().render(viewer, "itemtools.saveditems-entry",
                        Msg.unparsed("name", entry.name())))
                .lore(lore)
                .hideDetails()
                .build();
        return Button.of(icon, click -> give(click.player(), entry, 1))
                .onShiftLeft(click -> give(click.player(), entry, 10))
                .onRight(click -> confirmDelete(click.player(), entry));
    }

    private static int decodeAmount(SavedItemsStore.Entry entry) {
        try {
            ItemStack stack = ItemStack.deserializeBytes(entry.item());
            return stack == null || stack.isEmpty() ? 0 : stack.getAmount();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The stored stack as a menu icon; an unreadable entry shows a barrier instead of breaking the menu. */
    private ItemStack icon(SavedItemsStore.Entry entry) {
        try {
            ItemStack stack = ItemStack.deserializeBytes(entry.item());
            if (stack != null && !stack.isEmpty()) {
                return stack;
            }
        } catch (RuntimeException e) {
            support.ctx().logger().warn("Saved item '{}' is unreadable", entry.name(), e);
        }
        return new ItemStack(Material.BARRIER);
    }

    /** Runs on the viewer's thread (menu click). */
    void give(Player player, SavedItemsStore.Entry entry, int copies) {
        int limit = config.get().savedItemsGiveLimit();
        if (copies < 1 || copies > limit) {
            support.replyNow(player, "saveditems-invalid-amount", Msg.number("max", limit));
            return;
        }
        ItemStack template;
        try {
            template = ItemStack.deserializeBytes(entry.item());
        } catch (RuntimeException e) {
            support.ctx().logger().error("Saved item '{}' is unreadable", entry.name(), e);
            support.replyNow(player, "saveditems-unreadable", Msg.unparsed("name", entry.name()));
            return;
        }
        if (template == null || template.isEmpty()) {
            support.replyNow(player, "saveditems-unreadable", Msg.unparsed("name", entry.name()));
            return;
        }
        long total = (long) template.getAmount() * copies;
        if (total > Integer.MAX_VALUE) {
            support.replyNow(player, "saveditems-invalid-amount", Msg.number("max", limit));
            return;
        }
        int dropped = ItemValues.giveOrDrop(player, ItemValues.copies(template, (int) total));
        support.replyNow(player, dropped > 0 ? "saveditems-given-dropped" : "saveditems-given",
                Msg.unparsed("name", entry.name()), Msg.number("amount", total));
    }

    private void confirmDelete(Player viewer, SavedItemsStore.Entry entry) {
        Component title = support.ctx().messages().render(viewer, "itemtools.saveditems-delete-title",
                Msg.unparsed("name", entry.name()));
        List<Component> details = List.of(support.ctx().messages().render(viewer,
                "itemtools.saveditems-delete-details", Msg.unparsed("name", entry.name())));
        support.ctx().menus().confirm(viewer, title, details, click -> {
            Player player = click.player();
            store.delete(entry.name()).whenComplete((removed, error) -> {
                if (error != null) {
                    support.failed(player, "Deleting saved item " + entry.name(), error);
                    return;
                }
                support.reply(player, removed ? "saveditems-removed" : "saveditems-missing",
                        Msg.unparsed("name", entry.name()));
                open(player);
            });
        }, null);
    }
}
