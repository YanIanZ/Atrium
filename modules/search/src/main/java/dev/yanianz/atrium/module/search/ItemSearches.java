package dev.yanianz.atrium.module.search;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Runs {@code /search item}. Every player is read on their own Folia region thread with
 * {@code supplyOnEntity}; the futures are joined with a timeout so one slow or stuck read cannot
 * block the command. Partial results are reported instead of nothing.
 */
final class ItemSearches {
    enum Start { STARTED, ALREADY_RUNNING }

    private final ModuleContext ctx;
    private final Supplier<SearchConfig> config;
    private final Map<UUID, Session> active = new ConcurrentHashMap<>();
    private final Map<UUID, SearchReport> reports = new ConcurrentHashMap<>();

    ItemSearches(ModuleContext ctx, Supplier<SearchConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    Start start(CommandSender sender, ItemFilter filter, Player only) {
        UUID owner = Scans.owner(sender);
        SearchConfig cfg = config.get();
        List<Player> targets = new ArrayList<>();
        if (only != null) {
            targets.add(only);
        } else {
            targets.addAll(Bukkit.getOnlinePlayers());
        }
        boolean capped = targets.size() > cfg.maxPlayersPerSearch();
        if (capped) {
            targets = new ArrayList<>(targets.subList(0, cfg.maxPlayersPerSearch()));
        }

        Session session = new Session();
        if (active.putIfAbsent(owner, session) != null) {
            return Start.ALREADY_RUNNING;
        }
        if (targets.isEmpty()) {
            active.remove(owner, session);
            reports.remove(owner);
            Scans.deliver(ctx, owner, audience -> ctx.messages().send(audience, "search.no-players"));
            return Start.STARTED;
        }
        int playerCount = targets.size();
        Scans.deliver(ctx, owner, audience -> ctx.messages().send(audience, "search.started",
                Msg.unparsed("filter", filter.describe()), Msg.number("players", playerCount)));

        List<CompletableFuture<ItemScan.PlayerRow>> futures = new ArrayList<>(targets.size());
        for (Player target : targets) {
            String name = target.getName();
            UUID id = target.getUniqueId();
            CompletableFuture<ItemScan.PlayerRow> future;
            try {
                future = ctx.scheduler().supplyOnEntity(target, () -> ItemScan.scan(target, filter, ctx.logger()));
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot schedule the inventory scan of {}", id, e);
                futures.add(CompletableFuture.completedFuture(null));
                continue;
            }
            futures.add(future.handle((row, error) -> {
                if (error != null) {
                    if (unwrap(error) instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException) {
                        ctx.logger().info("The inventory scan of {} ({}) was skipped: the player left", name, id);
                    } else {
                        ctx.logger().warn("The inventory scan of {} ({}) failed", name, id, error);
                    }
                    return null;
                }
                return row;
            }));
        }

        CompletableFuture<?> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        all.completeOnTimeout(null, cfg.searchTimeoutSeconds(), TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        ctx.logger().warn("The item search of {} failed", owner, error);
                    }
                    complete(session, owner, filter, futures, capped);
                });
        return Start.STARTED;
    }

    private void complete(Session session, UUID owner, ItemFilter filter,
            List<CompletableFuture<ItemScan.PlayerRow>> futures, boolean capped) {
        boolean timedOut = false;
        int checked = 0;
        int failed = 0;
        List<ItemScan.PlayerRow> rows = new ArrayList<>();
        for (CompletableFuture<ItemScan.PlayerRow> future : futures) {
            if (!future.isDone()) {
                timedOut = true;
                continue;
            }
            try {
                ItemScan.PlayerRow row = future.getNow(null);
                if (row == null) {
                    failed++;
                    continue;
                }
                checked++;
                if (row.total() > 0) {
                    rows.add(row);
                }
            } catch (CancellationException e) {
                failed++;
            } catch (RuntimeException e) {
                ctx.logger().warn("Reading a completed inventory scan failed", e);
                failed++;
            }
        }
        rows.sort(Comparator.comparingLong(ItemScan.PlayerRow::total).reversed()
                .thenComparing(ItemScan.PlayerRow::name));
        boolean cancelled = session.cancelled;
        active.remove(owner, session);
        if (cancelled && active.containsKey(owner)) {
            // The owner already started a new search; do not overwrite its slot or report with this one.
            return;
        }
        SearchReport report = new SearchReport(filter, rows, checked, failed, timedOut, cancelled, capped,
                (System.nanoTime() - session.startedAt) / 1_000_000_000L);
        reports.put(owner, report);
        report.send(ctx, config.get(), owner, 1);
    }

    /** The last finished report of an owner, or {@code null}. */
    SearchReport report(UUID owner) {
        return reports.get(owner);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    boolean cancel(UUID owner) {
        Session session = active.get(owner);
        if (session == null) {
            return false;
        }
        session.cancelled = true;
        // Free the slot now so the owner can start another search; the pending reads still settle and
        // report once, unless a newer search already took the slot.
        active.remove(owner, session);
        return true;
    }

    void shutdown() {
        for (Session session : active.values()) {
            session.cancelled = true;
        }
        active.clear();
        reports.clear();
    }

    private static final class Session {
        final long startedAt = System.nanoTime();
        volatile boolean cancelled;
    }
}
