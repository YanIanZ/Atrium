package dev.yanianz.atrium.module.player;

import dev.yanianz.atrium.api.inventories.InventorySnapshotService;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.MenuProvider;
import dev.yanianz.atrium.core.menu.SlotLayout;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Read-only view and copy editor of the last QUIT snapshot of an offline player, built on {@code ctx.menus()}.
 *
 * <p>The editor works on a private copy and never exchanges items with the administrator: a left-click with a
 * cursor stack places (and consumes) that stack in an empty slot, a right- or shift-click destroys the slot.
 * The target therefore never gives an item to the administrator and the administrator never receives one from
 * the target. Removing a slot is a deliberate deletion: it is never refunded.
 *
 * <p>On close the copy is scheduled as a pending edit; the inventories module applies it on the next join only
 * while the live contents still match the base snapshot. The stacks the administrator added are stored with the
 * edit and returned to them when the edit is rejected.
 */
final class OfflineInventoryMenu {
    private static final int MAIN_ROWS = 6;
    private static final int ENDER_ROWS = 4;
    private static final int MAIN_ENDER_BUTTON = SlotLayout.bottom(MAIN_ROWS, SlotLayout.BACK_COLUMN);
    private static final int MAIN_INFO = SlotLayout.bottom(MAIN_ROWS, SlotLayout.PAGE_COLUMN);
    private static final int MAIN_CLOSE = SlotLayout.bottom(MAIN_ROWS, SlotLayout.CLOSE_COLUMN);
    private static final int ENDER_BACK = SlotLayout.bottom(ENDER_ROWS, SlotLayout.BACK_COLUMN);
    private static final int ENDER_INFO = SlotLayout.bottom(ENDER_ROWS, SlotLayout.PAGE_COLUMN);
    private static final int ENDER_CLOSE = SlotLayout.bottom(ENDER_ROWS, SlotLayout.CLOSE_COLUMN);
    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

    private final ModuleContext ctx;
    private final Player viewer;
    private final PlayerRef target;
    private final long snapshotId;
    private final long snapshotTime;
    private final boolean editable;
    private final boolean startEnder;
    /** False for /enderchest staff who may see only the ender chest, not the whole inventory. */
    private final boolean allowInventory;
    private final InventorySnapshotService service;
    /** Private copies; the snapshot itself is never modified. */
    private final List<ItemStack> inventory;
    private final List<ItemStack> ender;
    /**
     * Stacks placed by the administrator: refunded here only when the pending edit could not be stored at all.
     * A stored edit that is later rejected is refunded by the inventories module from its own recorded delta,
     * so the two paths never refund the same stack twice.
     */
    private final List<ItemStack> placed = new ArrayList<>();
    private long revision;
    private long savedRevision = -1;
    /** Saves are chained so an older page-flip save can never overwrite a newer one. */
    private CompletableFuture<?> saveChain = CompletableFuture.completedFuture(null);
    /** Written on the viewer's thread, read from the completion thread that reports the save. */
    private volatile long saveGeneration;

    OfflineInventoryMenu(ModuleContext ctx, Player viewer, PlayerRef target,
            InventorySnapshotService.Snapshot snapshot, boolean editable, boolean startEnder,
            boolean allowInventory) {
        this.ctx = ctx;
        this.viewer = viewer;
        this.target = target;
        this.snapshotId = snapshot.id();
        this.snapshotTime = snapshot.createdAt();
        this.editable = editable;
        this.startEnder = startEnder;
        this.allowInventory = allowInventory;
        this.service = ctx.services().get(InventorySnapshotService.class).orElse(null);
        this.inventory = copies(snapshot.inventory());
        this.ender = copies(snapshot.enderChest());
    }

    /** Must run on the viewer's thread. */
    void open() {
        if (!viewer.isOnline() || service == null) {
            return;
        }
        ctx.menus().open(viewer, startEnder || !allowInventory ? enderPage() : mainPage());
        if (ctx.menus().isViewing(viewer)) {
            ctx.messages().send(viewer, editable ? "player.offline-edit-help" : "player.offline-view-help",
                    Msg.unparsed("player", target.name()), Msg.unparsed("date", date()));
        }
    }

    private MenuProvider mainPage() {
        return menuViewer -> {
            Menu.Builder menu = Menu.builder(ctx.messages().render(menuViewer, "player.offline-title",
                    Msg.unparsed("player", target.name()), Msg.unparsed("date", date())), MAIN_ROWS);
            items(menu, inventory, SnapshotSlots.INVENTORY);
            menu.item(MAIN_ENDER_BUTTON, button(menuViewer, Material.ENDER_CHEST, "player.offline-ender-button",
                    "player.offline-ender-lore"), click -> {
                        if (allowInventory) {
                            click.replace(enderPage());
                        } else {
                            click.error();
                        }
                    });
            menu.item(MAIN_INFO, info(menuViewer));
            menu.item(MAIN_CLOSE, ItemBuilder.of(Material.BARRIER)
                    .name(ctx.messages().render(menuViewer, "core.menu.close")).build(), MenuClick::close);
            menu.onClose(this::onClose);
            return menu.build();
        };
    }

    private MenuProvider enderPage() {
        return menuViewer -> {
            Menu.Builder menu = Menu.builder(ctx.messages().render(menuViewer, "player.offline-ender-title",
                    Msg.unparsed("player", target.name()), Msg.unparsed("date", date())), ENDER_ROWS);
            items(menu, ender, SnapshotSlots.ENDER);
            if (allowInventory) {
                menu.item(ENDER_BACK, button(menuViewer, Material.ARROW, "player.offline-back-button",
                        "player.offline-back-lore"), click -> click.replace(mainPage()));
            }
            menu.item(ENDER_INFO, info(menuViewer));
            menu.item(ENDER_CLOSE, ItemBuilder.of(Material.BARRIER)
                    .name(ctx.messages().render(menuViewer, "core.menu.close")).build(), MenuClick::close);
            menu.onClose(this::onClose);
            return menu.build();
        };
    }

    /** Snapshot slot counts, kept in one place for the menu and its bounds checks. */
    private static final class SnapshotSlots {
        static final int INVENTORY = InventorySnapshotService.INVENTORY_SLOTS;
        static final int ENDER = InventorySnapshotService.ENDER_SLOTS;

        private SnapshotSlots() {}
    }

    private void items(Menu.Builder menu, List<ItemStack> slots, int count) {
        for (int slot = 0; slot < count; slot++) {
            ItemStack item = slot < slots.size() ? slots.get(slot) : null;
            ItemStack icon = item == null || item.isEmpty() ? ItemStack.empty() : item.clone();
            if (!editable) {
                menu.item(slot, icon);
            } else {
                int index = slot;
                menu.item(slot, icon, click -> slotClick(click, slots, index));
            }
        }
    }

    private void slotClick(MenuClick click, List<ItemStack> slots, int slot) {
        if (!editable || !click.isStillOpen() || slot < 0 || slot >= slots.size()) {
            return;
        }
        ClickType type = click.type();
        if (type == ClickType.LEFT) {
            ItemStack cursor = click.player().getItemOnCursor();
            if (cursor == null || cursor.isEmpty()) {
                click.error();
                return;
            }
            if (!empty(slots.get(slot))) {
                // Swapping would hand the target's item to the administrator: allow placing only in empty slots.
                click.error();
                return;
            }
            slots.set(slot, cursor.clone());
            click.player().setItemOnCursor(null);
            placed.add(cursor.clone());
            dirty();
            click.refresh();
            return;
        }
        if (type == ClickType.RIGHT || type == ClickType.SHIFT_LEFT || type == ClickType.SHIFT_RIGHT) {
            if (empty(slots.get(slot))) {
                click.error();
                return;
            }
            // Destroyed on purpose: removed target items must never reach the administrator's inventory.
            slots.set(slot, null);
            dirty();
            click.refresh();
            return;
        }
        click.error();
    }

    private ItemStack info(Player menuViewer) {
        List<Component> lore = new ArrayList<>();
        lore.add(ctx.messages().render(menuViewer, "player.offline-info-player",
                Msg.unparsed("player", target.name())));
        lore.add(ctx.messages().render(menuViewer, "player.offline-info-date",
                Msg.unparsed("date", date())));
        lore.add(ctx.messages().render(menuViewer, "player.offline-info-id", Msg.number("id", snapshotId)));
        lore.add(Component.empty());
        lore.add(ctx.messages().render(menuViewer,
                editable ? "player.offline-info-edit-hint" : "player.offline-info-readonly"));
        return ItemBuilder.of(editable ? Material.WRITABLE_BOOK : Material.BOOK)
                .name(ctx.messages().render(menuViewer, "player.offline-info-name",
                        Msg.unparsed("player", target.name())))
                .lore(lore)
                .build();
    }

    private ItemStack button(Player menuViewer, Material material, String nameKey, String loreKey) {
        return ItemBuilder.of(material).name(ctx.messages().render(menuViewer, nameKey))
                .lore(ctx.messages().render(menuViewer, loreKey)).build();
    }

    /**
     * Saves on every close, including a page flip, so a crash or kick cannot lose an edit that was still only
     * in this menu. Saves are chained and deduplicated by revision, so a flip before the final close costs at
     * most one extra write and never overwrites a newer copy.
     */
    private void onClose(Player closer, Inventory closed) {
        if (!editable || revision <= 0 || revision == savedRevision) {
            return;
        }
        List<ItemStack> inventoryCopy = copies(inventory);
        List<ItemStack> enderCopy = copies(ender);
        long revisionAtSave = revision;
        long generation = ++saveGeneration;
        saveChain = saveChain.handle((result, error) -> null).thenCompose(unused ->
                service.schedulePendingEdit(target.uuid(), snapshotId,
                        new InventorySnapshotService.Contents(inventoryCopy, enderCopy),
                        closer.getUniqueId(), closer.getName()))
                .whenComplete((pending, error) -> {
                    // Only the newest save may report, refund or clear the placed stacks.
                    if (generation != saveGeneration) {
                        return;
                    }
                    onViewer(() -> handleSave(pending, error, closer, revisionAtSave));
                });
    }

    private void handleSave(java.util.Optional<InventorySnapshotService.PendingEdit> pending, Throwable error,
            Player closer, long revisionAtSave) {
        if (error != null) {
            ctx.logger().error("Storing the offline edit of {} prepared by {} failed", target.uuid(),
                    closer.getName(), error);
            refund();
            message("player.offline-edit-failed");
            return;
        }
        if (pending == null || pending.isEmpty()) {
            refund();
            message("player.offline-edit-target-online", Msg.unparsed("player", target.name()));
            return;
        }
        placed.clear();
        savedRevision = Math.max(savedRevision, revisionAtSave);
        message("player.offline-edit-queued", Msg.unparsed("player", target.name()),
                Msg.unparsed("date", date()));
    }

    private void dirty() {
        revision++;
    }

    /** Gives the administrator back only the stacks they placed, never items that came from the snapshot. */
    private void refund() {
        for (ItemStack stack : placed) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            for (ItemStack left : viewer.getInventory().addItem(stack.clone()).values()) {
                viewer.getWorld().dropItem(viewer.getLocation(), left);
            }
        }
        placed.clear();
    }

    private void message(String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        if (viewer.isOnline()) {
            ctx.messages().send(viewer, key, tags);
        }
    }

    private void onViewer(Runnable action) {
        if (!viewer.isOnline()) {
            return;
        }
        ctx.scheduler().entity(viewer, action, null);
    }

    private String date() {
        return DATE.format(Instant.ofEpochMilli(snapshotTime));
    }

    private static List<ItemStack> copies(List<ItemStack> source) {
        List<ItemStack> copy = new ArrayList<>(source.size());
        for (ItemStack item : source) {
            copy.add(item == null || item.isEmpty() ? null : item.clone());
        }
        return copy;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.isEmpty();
    }
}
