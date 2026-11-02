package dev.yanianz.atrium.module.inventories;

import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.MenuProvider;
import dev.yanianz.atrium.core.menu.SlotLayout;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Read-only snapshot preview, built on {@code ctx.menus()}. The menu engine cancels every click and drag (the
 * menu has no input slots), each shown item is a display button holding a clone of the decoded snapshot, and the
 * menus close on quit, world change and module disable. There is no write path back to the stored bytes, so this
 * GUI can neither duplicate nor lose items.
 */
final class SnapshotPreview {
    private final ModuleContext ctx;

    /** The decoded snapshot; the lists hold private copies that are only ever shown as clones. */
    private record Snapshot(SnapshotStore.Meta meta, List<ItemStack> inventory, List<ItemStack> ender) {}

    SnapshotPreview(ModuleContext ctx) {
        this.ctx = ctx;
    }

    /** Must run on the viewer's thread. */
    void open(Player viewer, SnapshotStore.Meta meta, SnapshotCodec.Contents<ItemStack> contents) {
        if (!viewer.isOnline()) {
            return;
        }
        Snapshot snapshot = new Snapshot(meta, ItemStackSnapshots.copies(contents.inventory()),
                ItemStackSnapshots.copies(contents.enderChest()));
        ctx.menus().open(viewer, mainPage(snapshot, 0));
        if (ctx.menus().isViewing(viewer)) {
            ctx.messages().send(viewer, "inventories.preview-readonly");
        }
    }

    /**
     * The inventory section. It is sized from the stored section (41 slots on a vanilla server), so a larger
     * storage from a fork is paged instead of cut off.
     */
    private MenuProvider mainPage(Snapshot snapshot, int page) {
        return viewer -> {
            int slots = snapshot.inventory().size();
            int rows = SectionPages.rows(slots);
            Menu.Builder menu = Menu.builder(ctx.messages().render(viewer, "inventories.preview-title",
                    Msg.number("id", snapshot.meta().id()), Msg.unparsed("player", snapshot.meta().playerName())),
                    rows);
            show(menu, snapshot.inventory(), page);
            menu.item(SlotLayout.bottom(rows, SlotLayout.BACK_COLUMN), button(viewer, Material.ENDER_CHEST,
                    "inventories.preview-ender-button", "inventories.preview-ender-lore"),
                    click -> click.replace(enderPage(snapshot, 0)));
            navigation(menu, viewer, rows, slots, page, next -> mainPage(snapshot, next));
            menu.item(SlotLayout.bottom(rows, SlotLayout.PAGE_COLUMN), info(viewer, snapshot.meta()));
            menu.item(SlotLayout.bottom(rows, SlotLayout.CLOSE_COLUMN), ItemBuilder.of(Material.BARRIER)
                    .name(ctx.messages().render(viewer, "core.menu.close")).build(), MenuClick::close);
            return menu.build();
        };
    }

    /** The ender section, sized from the stored chest: three rows fit one page, six rows take two. */
    private MenuProvider enderPage(Snapshot snapshot, int page) {
        return viewer -> {
            int slots = snapshot.ender().size();
            int rows = SectionPages.rows(slots);
            Menu.Builder menu = Menu.builder(ctx.messages().render(viewer, "inventories.preview-ender-title",
                    Msg.number("id", snapshot.meta().id()), Msg.unparsed("player", snapshot.meta().playerName())),
                    rows);
            show(menu, snapshot.ender(), page);
            menu.item(SlotLayout.bottom(rows, SlotLayout.BACK_COLUMN), button(viewer, Material.ARROW,
                    "inventories.preview-back-button", "inventories.preview-back-lore"),
                    click -> click.replace(mainPage(snapshot, 0)));
            navigation(menu, viewer, rows, slots, page, next -> enderPage(snapshot, next));
            menu.item(SlotLayout.bottom(rows, SlotLayout.PAGE_COLUMN), info(viewer, snapshot.meta()));
            menu.item(SlotLayout.bottom(rows, SlotLayout.CLOSE_COLUMN), ItemBuilder.of(Material.BARRIER)
                    .name(ctx.messages().render(viewer, "core.menu.close")).build(), MenuClick::close);
            return menu.build();
        };
    }

    /** Previous/next buttons, only when the section needs more than one page. */
    private void navigation(Menu.Builder menu, Player viewer, int rows, int slots, int page,
            java.util.function.IntFunction<MenuProvider> pageProvider) {
        int pages = SectionPages.pages(slots);
        if (pages <= 1) {
            return;
        }
        int current = SectionPages.clamp(page, slots);
        if (current > 0) {
            menu.item(SlotLayout.bottom(rows, SlotLayout.PREVIOUS_COLUMN), ItemBuilder.of(Material.ARROW)
                    .name(ctx.messages().render(viewer, "core.menu.previous")).build(),
                    click -> click.replace(pageProvider.apply(current - 1)));
        }
        if (current < pages - 1) {
            menu.item(SlotLayout.bottom(rows, SlotLayout.NEXT_COLUMN), ItemBuilder.of(Material.ARROW)
                    .name(ctx.messages().render(viewer, "core.menu.next")).build(),
                    click -> click.replace(pageProvider.apply(current + 1)));
        }
    }

    /** Display-only copies of one page; empty snapshot slots stay empty instead of showing the filler. */
    private static void show(Menu.Builder menu, List<ItemStack> items, int page) {
        int first = SectionPages.first(page, items.size());
        int count = SectionPages.count(page, items.size());
        for (int slot = 0; slot < count; slot++) {
            ItemStack item = items.get(first + slot);
            if (item != null && !item.isEmpty()) {
                menu.item(slot, item);
            } else {
                menu.item(slot, ItemStack.empty());
            }
        }
    }

    private ItemStack info(Player viewer, SnapshotStore.Meta meta) {
        Component reason = ctx.messages().render(viewer, "inventories.reason." + meta.reason().key());
        return ItemBuilder.of(Material.PAPER)
                .name(ctx.messages().render(viewer, "inventories.preview-info-name", Msg.number("id", meta.id())))
                .lore(List.of(
                        ctx.messages().render(viewer, "inventories.preview-info-player",
                                Msg.unparsed("player", meta.playerName())),
                        ctx.messages().render(viewer, "inventories.preview-info-reason", Msg.component("reason", reason)),
                        ctx.messages().render(viewer, "inventories.preview-info-date",
                                Msg.unparsed("date", SnapshotText.time(meta.created()))),
                        ctx.messages().render(viewer, "inventories.preview-info-label",
                                Msg.component("label", meta.label() == null
                                        ? ctx.messages().render(viewer, "inventories.list-no-label")
                                        : Component.text(meta.label()))),
                        ctx.messages().render(viewer, "inventories.preview-info-stacks",
                                Msg.number("stacks", meta.stacks())),
                        Component.empty(),
                        ctx.messages().render(viewer, "inventories.preview-info-hint")))
                .build();
    }

    private ItemStack button(Player viewer, Material material, String nameKey, String loreKey) {
        return ItemBuilder.of(material).name(ctx.messages().render(viewer, nameKey))
                .lore(ctx.messages().render(viewer, loreKey)).build();
    }
}
