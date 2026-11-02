package dev.yanianz.atrium.module.kits;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.MenuProvider;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.menu.SlotLayout;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Read-only kit browser and item preview, built on {@code ctx.menus()}: every click and drag is cancelled by the
 * menu engine and every shown item is a copy (a display button), so nothing can be taken out. A click on a kit
 * looks the kit up again and re-checks the permission, so a kit removed or hidden meanwhile is never shown.
 */
final class KitPreview {
    private final ModuleContext ctx;
    private final KitService kits;
    private final Supplier<KitsConfig> config;

    KitPreview(ModuleContext ctx, KitService kits, Supplier<KitsConfig> config) {
        this.ctx = ctx;
        this.kits = kits;
        this.config = config;
    }

    private boolean mayUse(Player player, Kit kit) {
        return player.hasPermission(kit.permission()) || player.hasPermission("atrium.kit.admin");
    }

    private @org.jspecify.annotations.Nullable ItemStack icon(Kit kit) {
        try {
            return KitItems.deserializeIcon(kit.icon());
        } catch (RuntimeException e) {
            ctx.logger().warn("Kit {} has an unreadable icon", kit.name(), e);
            return null;
        }
    }

    // ---- browser ----

    void openBrowser(Player viewer) {
        ctx.menus().open(viewer, PagedMenu.builder(ctx.menus(), ctx.messages().render(viewer, "kits.preview-browser-title"))
                .rows(KitPages.rows(config.get().previewRows))
                .content(this::browserButtons)
                .build());
    }

    private List<Button> browserButtons(Player viewer) {
        List<Button> buttons = new ArrayList<>();
        for (Kit kit : kits.kits()) {
            if (!mayUse(viewer, kit)) continue;
            String name = kit.name();
            buttons.add(Button.of(browserItem(viewer, kit), click -> select(click, name)));
        }
        return buttons;
    }

    private void select(MenuClick click, String name) {
        Player viewer = click.player();
        Kit kit = kits.kit(name).filter(k -> mayUse(viewer, k)).orElse(null);
        if (kit == null) {
            click.error();
            ctx.messages().send(viewer, "kits.preview-gone", Msg.unparsed("kit", name));
            click.refresh();
            return;
        }
        click.open(itemsMenu(kit));
    }

    private ItemStack browserItem(Player viewer, Kit kit) {
        ItemStack base = icon(kit);
        var lore = new ArrayList<Component>();
        lore.add(costLine(viewer, kit));
        lore.add(ctx.messages().render(viewer, "kits.preview-kit-cooldown",
                Msg.duration("time", Duration.ofSeconds(kit.cooldownSeconds()))));
        lore.add(statusLine(viewer, kit));
        lore.add(ctx.messages().render(viewer, "kits.preview-kit-onetime",
                Msg.component("state", onOff(viewer, kit.oneTime()))));
        lore.add(kit.maxUses() < 0
                ? ctx.messages().render(viewer, "kits.preview-kit-maxuses-unlimited")
                : ctx.messages().render(viewer, "kits.preview-kit-maxuses", Msg.number("max", kit.maxUses())));
        lore.add(ctx.messages().render(viewer, "kits.preview-kit-permission", Msg.unparsed("permission", kit.permission())));
        lore.add(Component.empty());
        lore.add(ctx.messages().render(viewer, "kits.preview-kit-click"));
        ItemBuilder builder = base == null || base.isEmpty() ? ItemBuilder.of(Material.CHEST) : ItemBuilder.from(base).amount(1);
        return builder.name(ctx.messages().render(viewer, "kits.preview-kit-name", Msg.unparsed("kit", kit.name())))
                .lore(lore).hideDetails().build();
    }

    // ---- item view ----

    void open(Player viewer, Kit kit) {
        ctx.menus().open(viewer, itemsMenu(kit));
    }

    /** The kit items laid out like a player inventory ({@link KitLayout}); re-read on every render. */
    private MenuProvider itemsMenu(Kit requested) {
        String name = requested.name();
        return viewer -> {
            Kit kit = kits.kit(name).orElse(requested);
            Menu.Builder menu = Menu.builder(ctx.messages().render(viewer, "kits.preview-title",
                    Msg.unparsed("kit", kit.name())), KitLayout.ROWS);
            ItemStack[] items = KitItems.deserialize(kit.items());
            for (int i = 0; i < items.length; i++) {
                int slot = KitLayout.displaySlot(i);
                if (slot >= 0 && items[i] != null && !items[i].isEmpty()) menu.item(slot, items[i]);
            }
            menu.item(KitLayout.INFO, infoItem(viewer, kit));
            if (ctx.menus().hasHistory(viewer)) {
                menu.item(SlotLayout.bottom(KitLayout.ROWS, SlotLayout.BACK_COLUMN), ItemBuilder.of(Material.ARROW)
                        .name(ctx.messages().render(viewer, "kits.preview-back")).build(), MenuClick::back);
            }
            menu.item(SlotLayout.bottom(KitLayout.ROWS, SlotLayout.CLOSE_COLUMN), ItemBuilder.of(Material.BARRIER)
                    .name(ctx.messages().render(viewer, "core.menu.close")).build(), MenuClick::close);
            return menu.build();
        };
    }

    private ItemStack infoItem(Player viewer, Kit kit) {
        var lore = new ArrayList<Component>();
        lore.add(costLine(viewer, kit));
        lore.add(ctx.messages().render(viewer, "kits.preview-info-cooldown",
                Msg.duration("time", Duration.ofSeconds(kit.cooldownSeconds()))));
        lore.add(statusLine(viewer, kit));
        lore.add(ctx.messages().render(viewer, "kits.preview-info-onetime",
                Msg.component("state", onOff(viewer, kit.oneTime()))));
        lore.add(kit.maxUses() < 0
                ? ctx.messages().render(viewer, "kits.preview-info-maxuses-unlimited")
                : ctx.messages().render(viewer, "kits.preview-info-maxuses", Msg.number("max", kit.maxUses())));
        lore.add(ctx.messages().render(viewer, "kits.preview-info-permission",
                Msg.unparsed("permission", kit.permission())));
        return ItemBuilder.of(Material.BOOK)
                .name(ctx.messages().render(viewer, "kits.preview-info-title", Msg.unparsed("kit", kit.name())))
                .lore(lore).build();
    }

    private Component costLine(Player viewer, Kit kit) {
        if (kit.cost().signum() <= 0) return ctx.messages().render(viewer, "kits.preview-info-free");
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        String cost = economy != null ? economy.format(kit.cost()) : kit.cost().toPlainString();
        return ctx.messages().render(viewer, "kits.preview-info-cost", Msg.unparsed("cost", cost));
    }

    private Component statusLine(Player viewer, Kit kit) {
        KitUsage.Availability availability = kits.availability(viewer, kit, null);
        Component status = switch (availability.status()) {
            case AVAILABLE -> ctx.messages().render(viewer, "kits.preview-status-available");
            case COOLDOWN -> ctx.messages().render(viewer, "kits.preview-status-cooldown",
                    Msg.duration("time", availability.remaining()));
            case ALREADY_CLAIMED -> ctx.messages().render(viewer, "kits.preview-status-claimed");
            case MAX_USES -> ctx.messages().render(viewer, "kits.preview-status-maxuses",
                    Msg.number("max", kit.maxUses()));
        };
        return ctx.messages().render(viewer, "kits.preview-info-status", Msg.component("status", status));
    }

    private Component onOff(Player viewer, boolean value) {
        return ctx.messages().render(viewer, value ? "kits.editor-on" : "kits.editor-off");
    }
}
