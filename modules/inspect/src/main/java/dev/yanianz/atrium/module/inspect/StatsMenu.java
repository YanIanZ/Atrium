package dev.yanianz.atrium.module.inspect;

import dev.yanianz.atrium.api.statistics.StatisticsCatalog;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The {@code /stats} inventory: one icon per Bukkit statistic, grouped and paged. */
final class StatsMenu {
    private final ModuleContext ctx;
    private final String targetName;
    private final List<StatsView.Row> rows;

    StatsMenu(ModuleContext ctx, String targetName, List<StatsView.Row> rows) {
        this.ctx = ctx;
        this.targetName = targetName;
        this.rows = List.copyOf(rows);
    }

    void open(Player viewer) {
        ctx.menus().open(viewer, PagedMenu.builder(ctx.menus(), title(viewer))
                .rows(6)
                .content(this::buttons)
                .build());
    }

    private Component title(Player viewer) {
        return ctx.messages().render(viewer, "inspect.stats-title",
                Msg.unparsed("player", targetName), Msg.number("count", rows.size()));
    }

    private List<Button> buttons(Player viewer) {
        List<Button> buttons = new ArrayList<>();
        for (StatsView.Row row : rows) {
            buttons.add(Button.of(icon(viewer, row)));
        }
        return buttons;
    }

    private org.bukkit.inventory.ItemStack icon(Player viewer, StatsView.Row row) {
        StatisticsCatalog.Entry entry = row.entry();
        String display = InspectFormat.prettyKey(entry.key());
        Component value = Component.text(StatisticsCatalog.format(entry, row.value()));
        return ItemBuilder.of(iconFor(entry.category()))
                .name(ctx.messages().render(viewer, "inspect.stats-entry-name", Msg.unparsed("statistic", display)))
                .lore(ctx.messages().render(viewer, "inspect.stats-entry-value", Msg.component("value", value)),
                        ctx.messages().render(viewer, "inspect.stats-entry-raw",
                                Msg.unparsed("key", entry.key()), Msg.number("raw", row.value())),
                        ctx.messages().render(viewer, "inspect.stats-entry-category",
                                Msg.unparsed("category", entry.category().name().toLowerCase(java.util.Locale.ROOT))))
                .hideDetails()
                .build();
    }

    static Material iconFor(StatisticsCatalog.Category category) {
        return switch (category) {
            case MOVEMENT -> Material.GOLDEN_BOOTS;
            case COMBAT -> Material.IRON_SWORD;
            case WORLD -> Material.GRASS_BLOCK;
            case INTERACTION -> Material.BELL;
            case ITEMS -> Material.CHEST;
            case OTHER -> Material.PAPER;
        };
    }
}
