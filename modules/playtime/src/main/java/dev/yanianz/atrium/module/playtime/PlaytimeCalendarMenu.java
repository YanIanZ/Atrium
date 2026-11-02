package dev.yanianz.atrium.module.playtime;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.MenuClick;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * {@code /cplaytime}: a six-week calendar where every day shows the stored active playtime. Navigation
 * buttons switch the month and re-read only that month; the menu never blocks a tick thread.
 */
final class PlaytimeCalendarMenu {
    private final ModuleContext ctx;
    private final PlaytimeModule module;
    private final ConfigHandle<PlaytimeConfig> config;

    PlaytimeCalendarMenu(ModuleContext ctx, PlaytimeModule module, ConfigHandle<PlaytimeConfig> config) {
        this.ctx = ctx;
        this.module = module;
        this.config = config;
    }

    void open(Player viewer, UUID target, String targetName, LocalDate month) {
        module.calendar(target, month).whenComplete((days, error) -> {
            if (error != null) {
                ctx.logger().warn("Reading the playtime calendar of {} failed", targetName, error);
                onViewer(viewer, () -> ctx.messages().send(viewer, "core.error"));
                return;
            }
            onViewer(viewer, () -> ctx.menus().open(viewer, build(viewer, target, targetName, month, days)));
        });
    }

    private Menu build(Player viewer, UUID target, String targetName, LocalDate month, Map<LocalDate, Long> days) {
        Locale locale = viewer.locale();
        ZoneId zone = ZoneId.systemDefault();
        Component title = ctx.messages().render(viewer, "playtime.calendar-title",
                Msg.unparsed("player", targetName),
                Msg.unparsed("month", PlaytimeCalendar.monthTitle(month, locale)));
        Menu.Builder menu = Menu.builder(title, 6);
        List<PlaytimeCalendar.Cell> cells = PlaytimeCalendar.cells(month,
                PlaytimeCalendar.firstDay(config.get().calendarFirstDay));
        long total = 0L;
        for (int i = 0; i < cells.size(); i++) {
            PlaytimeCalendar.Cell cell = cells.get(i);
            int slot = (i / PlaytimeCalendar.COLUMNS) * 9 + (i % PlaytimeCalendar.COLUMNS);
            if (!cell.inMonth()) {
                menu.item(slot, otherMonth(viewer, cell.date(), locale));
                continue;
            }
            long seconds = Math.max(0L, days.getOrDefault(cell.date(), 0L));
            total += seconds;
            menu.item(slot, day(viewer, cell.date(), seconds, locale, zone));
        }
        menu.item(7, ItemBuilder.of(Material.SPECTRAL_ARROW)
                        .name(ctx.messages().render(viewer, "playtime.calendar-previous"))
                        .build(),
                click -> open(viewer, target, targetName, PlaytimeCalendar.previousMonth(month)));
        menu.item(8, ItemBuilder.of(Material.SPECTRAL_ARROW)
                        .name(ctx.messages().render(viewer, "playtime.calendar-next"))
                        .build(),
                click -> open(viewer, target, targetName, PlaytimeCalendar.nextMonth(month)));
        menu.item(52, ItemBuilder.of(Material.CLOCK)
                .name(ctx.messages().render(viewer, "playtime.calendar-summary",
                        Msg.unparsed("player", targetName), Msg.duration("total",
                                java.time.Duration.ofSeconds(total))))
                .lore(ctx.messages().render(viewer, "playtime.calendar-weekdays",
                        Msg.unparsed("days", weekdayLine(
                                PlaytimeCalendar.firstDay(config.get().calendarFirstDay), locale))))
                .hideDetails()
                .build());
        menu.item(53, ItemBuilder.of(Material.BARRIER)
                        .name(ctx.messages().render(viewer, "core.menu.close"))
                        .build(),
                MenuClick::close);
        return menu.build();
    }

    private org.bukkit.inventory.ItemStack day(Player viewer, LocalDate date, long seconds, Locale locale,
            ZoneId zone) {
        boolean today = date.equals(LocalDate.now(zone));
        ItemBuilder icon = ItemBuilder.of(seconds > 0 ? Material.CLOCK : Material.PAPER)
                .name(ctx.messages().render(viewer, "playtime.calendar-day-name",
                        Msg.unparsed("date", PlaytimeCalendar.dayName(date, locale))))
                .lore(seconds > 0
                        ? ctx.messages().render(viewer, "playtime.calendar-day-time",
                                Msg.duration("time", java.time.Duration.ofSeconds(seconds)))
                        : ctx.messages().render(viewer, "playtime.calendar-day-empty"))
                .glint(today)
                .hideDetails();
        if (today) {
            icon.addLore(ctx.messages().render(viewer, "playtime.calendar-today"));
        }
        return icon.build();
    }

    private org.bukkit.inventory.ItemStack otherMonth(Player viewer, LocalDate date, Locale locale) {
        return ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(ctx.messages().render(viewer, "playtime.calendar-other",
                        Msg.unparsed("date", PlaytimeCalendar.dayName(date, locale))))
                .hideDetails()
                .build();
    }

    private String weekdayLine(java.time.DayOfWeek firstDay, Locale locale) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < PlaytimeCalendar.COLUMNS; i++) {
            if (i > 0) {
                line.append(", ");
            }
            line.append(PlaytimeCalendar.weekdayName(firstDay.plus(i), locale));
        }
        return line.toString();
    }

    private void onViewer(Player viewer, Runnable work) {
        try {
            ctx.scheduler().entity(viewer, work, null);
        } catch (Throwable t) {
            ctx.logger().warn("Scheduling the playtime calendar for {} failed", viewer.getName(), t);
        }
    }
}
