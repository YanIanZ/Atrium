package dev.yanianz.atrium.module.search;

import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;

/**
 * Finished result of one {@code /search item} run. Immutable; {@code /search results} paginates it.
 * Player rows are sorted by amount so the biggest holdings come first.
 */
record SearchReport(
        ItemFilter filter,
        List<ItemScan.PlayerRow> rows,
        int checked,
        int failed,
        boolean timedOut,
        boolean cancelled,
        boolean capped,
        long seconds) {

    SearchReport {
        rows = List.copyOf(rows);
    }

    void send(ModuleContext ctx, SearchConfig config, UUID owner, int page) {
        Scans.deliver(ctx, owner, audience -> render(ctx.messages(), config, audience, page));
    }

    private void render(Messages messages, SearchConfig config, Audience audience, int page) {
        if (cancelled) {
            messages.send(audience, "search.cancelled-summary",
                    Msg.number("seconds", seconds),
                    Msg.number("checked", checked),
                    Msg.number("found", rows.size()));
        } else {
            messages.send(audience, "search.done",
                    Msg.number("checked", checked),
                    Msg.number("seconds", seconds),
                    Msg.number("found", rows.size()),
                    Msg.number("items", totalItems()));
            if (failed > 0) {
                messages.send(audience, "search.done-failed", Msg.number("failed", failed));
            }
            if (timedOut) {
                messages.send(audience, "search.done-timeout");
            }
            if (capped) {
                messages.send(audience, "search.done-capped");
            }
        }
        if (rows.isEmpty()) {
            messages.send(audience, "search.results-empty");
            return;
        }
        int pageSize = config.resultsPageSize();
        int pages = Pages.count(rows.size(), pageSize);
        int shown = Pages.clamp(page, rows.size(), pageSize);
        messages.send(audience, "search.results-header",
                Msg.unparsed("filter", filter.describe()),
                Msg.number("page", shown),
                Msg.number("pages", pages),
                Msg.number("players", rows.size()));
        for (ItemScan.PlayerRow row : Pages.slice(rows, shown, pageSize)) {
            messages.send(audience, "search.results-row",
                    Msg.unparsed("player", row.name()),
                    Msg.number("amount", row.total()),
                    Msg.number("stacks", row.stacks()),
                    Msg.number("inventory", row.inventory()),
                    Msg.number("ender", row.ender()),
                    Msg.number("shulker", row.shulker()));
        }
        if (shown < pages) {
            messages.send(audience, "search.results-footer", Msg.number("next", shown + 1));
        }
    }

    private long totalItems() {
        long total = 0;
        for (ItemScan.PlayerRow row : rows) {
            total += row.total();
        }
        return total;
    }
}
