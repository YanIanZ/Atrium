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
    static final int MAIN_ROWS = 6;
    static final int ENDER_ROWS = 4;
    private static final int ENDER_BUTTON = SlotLayout.bottom(MAIN_ROWS, SlotLayout.BACK_COLUMN);
    private static final int INFO_SLOT = SlotLayout.bottom(MAIN_ROWS, SlotLayout.PAGE_COLUMN);
    private static final int MAIN_CLOSE = SlotLayout.bottom(MAIN_ROWS, SlotLayout.CLOSE_COLUMN);
    private static final int BACK_BUTTON = SlotLayout.bottom(ENDER_ROWS, SlotLayout.BACK_COLUMN);
    private static final int ENDER_INFO = SlotLayout.bottom(ENDER_ROWS, SlotLayout.PAGE_COLUMN);
    private static final int ENDER_CLOSE = SlotLayout.bottom(ENDER_ROWS, SlotLayout.CLOSE_COLUMN);

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
        ctx.menus().open(viewer, mainPage(snapshot));
        if (ctx.menus().isViewing(viewer)) {
            ctx.messages().send(viewer, "inventories.preview-readonly");
        }
    }

    private MenuProvider mainPage(Snapshot snapshot) {
        return viewer -> {
            Menu.Builder menu = Menu.builder(ctx.messages().render(viewer, "inventories.preview-title",
                    Msg.number("id", snapshot.meta().id()), Msg.unparsed("player", snapshot.meta().playerName())),
                    MAIN_ROWS);
            show(menu, snapshot.inventory(), SnapshotCodec.INVENTORY_SLOTS);
            menu.item(ENDER_BUTTON, button(viewer, Material.ENDER_CHEST, "inventories.preview-ender-button",
                    "inventories.preview-ender-lore"), click -> click.replace(enderPage(snapshot)));
            menu.item(INFO_SLOT, info(viewer, snapshot.meta()));
            menu.item(MAIN_CLOSE, ItemBuilder.of(Material.BARRIER)
                    .name(ctx.messages().render(viewer, "core.menu.close")).build(), MenuClick::close);
            return menu.build();
        };
    }

    private MenuProvider enderPage(Snapshot snapshot) {
        return viewer -> {
            Menu.Builder menu = Menu.builder(ctx.messages().render(viewer, "inventories.preview-ender-title",
                    Msg.number("id", snapshot.meta().id()), Msg.unparsed("player", snapshot.meta().playerName())),
                    ENDER_ROWS);
            show(menu, snapshot.ender(), SnapshotCodec.ENDER_SLOTS);
            menu.item(BACK_BUTTON, button(viewer, Material.ARROW, "inventories.preview-back-button",
                    "inventories.preview-back-lore"), click -> click.replace(mainPage(snapshot)));
            menu.item(ENDER_INFO, info(viewer, snapshot.meta()));
            menu.item(ENDER_CLOSE, ItemBuilder.of(Material.BARRIER)
                    .name(ctx.messages().render(viewer, "core.menu.close")).build(), MenuClick::close);
            return menu.build();
        };
    }

    /** Display-only copies; empty snapshot slots stay empty instead of showing the filler. */
    private static void show(Menu.Builder menu, List<ItemStack> items, int slots) {
        for (int slot = 0; slot < slots; slot++) {
            ItemStack item = slot < items.size() ? items.get(slot) : null;
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
