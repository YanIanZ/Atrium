package dev.yanianz.atrium.core.menu;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A list of buttons spread over pages, with the standard navigation bar in the bottom row: back (only when
 * there is history), previous, page indicator, next, close. The content function runs on every render, so
 * {@link MenuService#refresh} and back-navigation always show current data.
 *
 * <pre>{@code
 * ctx.menus().open(player, PagedMenu.builder(ctx.menus(), title)
 *         .content(viewer -> homes(viewer).stream().map(h -> homeButton(viewer, h)).toList())
 *         .build());
 * }</pre>
 */
public final class PagedMenu implements MenuProvider {
    private final MenuService menus;
    private final Component title;
    private final int rows;
    private final int[] slots;
    private final Function<Player, List<Button>> content;
    private final @Nullable BiConsumer<Player, Menu.Builder> decorate;
    private final int page;

    private PagedMenu(MenuService menus, Component title, int rows, int[] slots,
            Function<Player, List<Button>> content, @Nullable BiConsumer<Player, Menu.Builder> decorate, int page) {
        this.menus = menus;
        this.title = title;
        this.rows = rows;
        this.slots = slots;
        this.content = content;
        this.decorate = decorate;
        this.page = page;
    }

    public static Builder builder(MenuService menus, Component title) {
        return new Builder(menus, title);
    }

    /** 0-based page this provider renders (clamped on render). */
    public int page() {
        return page;
    }

    /** The same menu on another page. */
    public PagedMenu page(int newPage) {
        return new PagedMenu(menus, title, rows, slots, content, decorate, Math.max(0, newPage));
    }

    @Override
    public Menu render(Player viewer) {
        List<Button> items = content.apply(viewer);
        Paginator pages = new Paginator(items.size(), slots.length);
        int current = pages.clamp(page);
        Menu.Builder menu = Menu.builder(title, rows);
        List<Button> visible = pages.slice(items, current);
        for (int i = 0; i < visible.size(); i++) {
            menu.button(slots[i], visible.get(i));
        }
        if (items.isEmpty()) {
            menu.item(slots[Math.min(slots.length - 1, slots.length / 2)], ItemBuilder.of(Material.STRUCTURE_VOID)
                    .name(menus.text(viewer, "core.menu.empty")).build());
        }
        if (menus.hasHistory(viewer)) {
            menu.button(SlotLayout.bottom(rows, SlotLayout.BACK_COLUMN), Button.of(
                    ItemBuilder.of(Material.ARROW).name(menus.text(viewer, "core.menu.back")).build(), MenuClick::back));
        }
        if (pages.hasPrevious(current)) {
            menu.button(SlotLayout.bottom(rows, SlotLayout.PREVIOUS_COLUMN), Button.of(
                    ItemBuilder.of(Material.SPECTRAL_ARROW).name(menus.text(viewer, "core.menu.previous")).build(),
                    click -> click.replace(page(current - 1))));
        }
        if (pages.pageCount() > 1) {
            menu.item(SlotLayout.bottom(rows, SlotLayout.PAGE_COLUMN), ItemBuilder.of(Material.PAPER)
                    .amount(Math.min(current + 1, 64))
                    .name(menus.text(viewer, "core.menu.page",
                            dev.yanianz.atrium.core.message.Msg.number("page", current + 1),
                            dev.yanianz.atrium.core.message.Msg.number("pages", pages.pageCount())))
                    .build());
        }
        if (pages.hasNext(current)) {
            menu.button(SlotLayout.bottom(rows, SlotLayout.NEXT_COLUMN), Button.of(
                    ItemBuilder.of(Material.SPECTRAL_ARROW).name(menus.text(viewer, "core.menu.next")).build(),
                    click -> click.replace(page(current + 1))));
        }
        menu.button(SlotLayout.bottom(rows, SlotLayout.CLOSE_COLUMN), Button.of(
                ItemBuilder.of(Material.BARRIER).name(menus.text(viewer, "core.menu.close")).build(), MenuClick::close));
        if (decorate != null) {
            decorate.accept(viewer, menu);
        }
        return menu.build();
    }

    public static final class Builder {
        private final MenuService menus;
        private final Component title;
        private int rows = 6;
        private int @Nullable [] slots;
        private Function<Player, List<Button>> content = viewer -> List.of();
        private @Nullable BiConsumer<Player, Menu.Builder> decorate;
        private int page;

        private Builder(MenuService menus, Component title) {
            this.menus = Objects.requireNonNull(menus);
            this.title = Objects.requireNonNull(title);
        }

        /** 2-6 rows; the bottom row is the navigation bar (default 6). */
        public Builder rows(int rows) {
            if (SlotLayout.checkRows(rows) < 2) {
                throw new IllegalArgumentException("A paged menu needs at least 2 rows");
            }
            this.rows = rows;
            return this;
        }

        /** Slots used for content (default: every slot above the navigation bar). */
        public Builder slots(int... slots) {
            this.slots = slots.clone();
            return this;
        }

        /** Content computed per render (on the viewer's thread). */
        public Builder content(Function<Player, List<Button>> content) {
            this.content = Objects.requireNonNull(content);
            return this;
        }

        public Builder content(List<Button> content) {
            List<Button> copy = List.copyOf(content);
            this.content = viewer -> copy;
            return this;
        }

        /** Extra buttons added after content and navigation (may override navigation slots). */
        public Builder decorate(BiConsumer<Player, Menu.Builder> decorate) {
            this.decorate = decorate;
            return this;
        }

        /** Start page (0-based). */
        public Builder page(int page) {
            this.page = Math.max(0, page);
            return this;
        }

        public PagedMenu build() {
            int[] area = slots != null ? slots : SlotLayout.contentAboveNavigation(rows);
            int max = SlotLayout.size(rows) - SlotLayout.COLUMNS;
            for (int slot : area) {
                if (slot < 0 || slot >= max) {
                    throw new IllegalArgumentException("Content slot " + slot + " is outside the content area");
                }
            }
            if (area.length == 0) {
                throw new IllegalArgumentException("A paged menu needs at least one content slot");
            }
            return new PagedMenu(menus, title, rows, area, content, decorate, page);
        }
    }
}
