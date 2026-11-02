package dev.yanianz.atrium.module.warps;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jspecify.annotations.Nullable;

/**
 * The paginated /warps chest menu, built on {@code ctx.menus()}: every item is display-only and the menu engine
 * cancels all clicks and drags. A click never trusts the item shown when the menu was drawn: the warp is looked
 * up again and the permission is checked again, so a warp removed or hidden in the meantime cannot be used
 * through an old menu. Page flips re-read the warp list.
 *
 * <p>A click goes through the same {@link WarpsModule#useWarp} path as {@code /warp}, so cost, cooldown,
 * random-location decks and the warmup rules cannot differ between the command and the menu.
 */
final class WarpMenu {
    private final ModuleContext ctx;
    private final WarpsModule module;
    private final Messages messages;
    private final ConfigHandle<WarpsConfig> config;

    /** A header row (category) or a warp row of the flattened page list. */
    private record Row(String category, int categoryCount, @Nullable Warp warp) {}

    WarpMenu(ModuleContext ctx, WarpsModule module, ConfigHandle<WarpsConfig> config) {
        this.ctx = ctx;
        this.module = module;
        this.messages = ctx.messages();
        this.config = config;
    }

    void open(Player viewer, @Nullable String category) {
        ctx.menus().open(viewer, PagedMenu.builder(ctx.menus(), title(viewer, category))
                .rows(WarpPages.rows(config.get().guiRows))
                .content(player -> buttons(player, category))
                .build());
    }

    private Component title(Player viewer, @Nullable String category) {
        return category == null
                ? messages.render(viewer, "warps.gui-title")
                : messages.render(viewer, "warps.gui-title-category", Msg.unparsed("category", category));
    }

    private List<Button> buttons(Player viewer, @Nullable String category) {
        List<Button> buttons = new ArrayList<>();
        for (Row row : buildRows(viewer, category)) {
            Warp warp = row.warp();
            if (warp == null) {
                buttons.add(Button.of(header(viewer, row)));
            } else {
                String name = warp.name();
                buttons.add(Button.of(warpItem(viewer, warp), click -> use(click, name)));
            }
        }
        if (buttons.isEmpty()) {
            buttons.add(Button.of(ItemBuilder.of(Material.BARRIER)
                    .name(messages.render(viewer, "warps.gui-empty")).build()));
        }
        return buttons;
    }

    /** Warps the viewer may use, grouped by category (nearest page order first, then by name). */
    private List<Row> buildRows(Player viewer, @Nullable String category) {
        List<Warp> usable = new ArrayList<>();
        for (Warp warp : module.manager().all()) {
            if (!module.canUse(viewer, warp)) {
                continue;
            }
            if (category != null && !warp.category().equalsIgnoreCase(category)) {
                continue;
            }
            usable.add(warp);
        }
        usable.sort(Comparator.comparing(Warp::category, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Warp::name, String.CASE_INSENSITIVE_ORDER));
        Map<String, List<Warp>> byCategory = new LinkedHashMap<>();
        for (Warp warp : usable) {
            byCategory.computeIfAbsent(warp.category(), key -> new ArrayList<>()).add(warp);
        }
        List<Row> rows = new ArrayList<>(usable.size() + byCategory.size());
        byCategory.forEach((name, warps) -> {
            rows.add(new Row(name, warps.size(), null));
            warps.forEach(warp -> rows.add(new Row(name, warps.size(), warp)));
        });
        return rows;
    }

    private ItemStack header(Player viewer, Row row) {
        return ItemBuilder.of(Material.BOOKSHELF)
                .name(messages.render(viewer, "warps.gui-category", Msg.unparsed("category", row.category()),
                        Msg.number("count", row.categoryCount())))
                .build();
    }

    @SuppressWarnings("deprecation") // integer custom model data is what warps.yml stores
    private ItemStack warpItem(Player viewer, Warp warp) {
        WarpsConfig cfg = config.get();
        WarpsConfig.Icon configured = cfg.icon(warp.key());
        String materialName = configured == null ? cfg.defaultIcon : configured.material;
        Material material = material(materialName);
        ItemStack item = new ItemStack(material);
        int modelData = configured == null ? -1 : configured.customModelData;
        if (modelData >= 0) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setCustomModelData(modelData);
                item.setItemMeta(meta);
            }
        }
        var location = warp.location();
        List<Component> lore = new ArrayList<>(List.of(
                messages.render(viewer, "warps.gui-item-location", Msg.unparsed("world", location.world()),
                        Msg.number("x", location.blockX()), Msg.number("y", location.blockY()),
                        Msg.number("z", location.blockZ())),
                messages.render(viewer, "warps.gui-item-category", Msg.unparsed("category", warp.category()))));
        double cost = cfg.cost(warp.key());
        if (cost > 0) {
            lore.add(messages.render(viewer, "warps.gui-item-cost", Msg.unparsed("cost", number(cost))));
        }
        long cooldown = cfg.cooldownSeconds(warp.key());
        if (cooldown > 0) {
            lore.add(messages.render(viewer, "warps.gui-item-cooldown", Msg.number("seconds", cooldown)));
        }
        int locations = module.poolSize(warp);
        if (locations > 1) {
            lore.add(messages.render(viewer, "warps.gui-item-locations", Msg.number("count", locations)));
        }
        lore.add(Component.empty());
        lore.add(messages.render(viewer, "warps.gui-item-click"));
        return ItemBuilder.from(item)
                .name(messages.render(viewer, "warps.gui-item-name", Msg.unparsed("warp", warp.name())))
                .lore(lore)
                .hideDetails()
                .build();
    }

    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static Material material(@Nullable String name) {
        if (name != null && !name.isBlank()) {
            Material matched = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (matched != null && matched.isItem() && !matched.isAir()) {
                return matched;
            }
        }
        return Material.CHEST;
    }

    // ---- clicks (run on the viewer's thread) ----

    private void use(MenuClick click, String name) {
        Player viewer = click.player();
        Optional<Warp> found = module.manager().get(name);
        if (found.isEmpty() || !module.canUse(viewer, found.get())) {
            // The warp disappeared or became hidden while the menu was open: never teleport from stale data.
            click.error();
            messages.send(viewer, "warps.not-found", Msg.unparsed("warp", name));
            click.refresh();
            return;
        }
        click.close();
        module.useWarp(viewer, found.get(), null);
    }
}
