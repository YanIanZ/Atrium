package dev.yanianz.atrium.module.performance;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * One-shot lag report. Nothing is sampled periodically: every value is read when the command runs,
 * on the thread that owns it. On Folia the region TPS/MSPT methods are only valid from a region
 * thread, so every online player supplies one sample on its own region.
 */
final class LagReporter {
    record RegionSample(String thread, String world, String player, double tps1, double tps5, double tps15,
            double mspt) {}

    record WorldCounts(String world, int entities, int chunks, int players) {}

    private static final class RegionAgg {
        private final String thread;
        private String world;
        private String firstPlayer;
        private double tps1 = Double.NaN;
        private double tps5 = Double.NaN;
        private double tps15 = Double.NaN;
        private double mspt = Double.NaN;
        private int players = 1;

        RegionAgg(RegionSample sample) {
            this.thread = sample.thread();
            this.world = sample.world();
            this.firstPlayer = sample.player();
            mergeValues(sample);
        }

        void add(RegionSample sample) {
            players++;
            if (world == null) {
                world = sample.world();
            }
            mergeValues(sample);
        }

        private void mergeValues(RegionSample sample) {
            if (!Double.isFinite(tps1) && Double.isFinite(sample.tps1())) {
                tps1 = sample.tps1();
                tps5 = sample.tps5();
                tps15 = sample.tps15();
            }
            if (!Double.isFinite(mspt) && Double.isFinite(sample.mspt())) {
                mspt = sample.mspt();
            }
        }

        String label() {
            return players > 1 ? firstPlayer + " +" + (players - 1) : firstPlayer;
        }
    }

    private final ModuleContext ctx;
    private final Supplier<PerformanceConfig> config;
    private final EntityLimitListener limits;

    LagReporter(ModuleContext ctx, Supplier<PerformanceConfig> config, EntityLimitListener limits) {
        this.ctx = ctx;
        this.config = config;
        this.limits = limits;
    }

    int report(CommandSender sender) {
        PerformanceConfig cfg = config.get();
        boolean folia = ctx.scheduler().isFolia();
        double[] tps = tps();
        double mspt = mspt();
        RegionSample self = new RegionSample(Thread.currentThread().getName(),
                sender instanceof Player player ? player.getWorld().getName() : null,
                sender instanceof Player player ? player.getName() : "console",
                tps == null ? Double.NaN : tps[0],
                tps == null ? Double.NaN : tps[1],
                tps == null ? Double.NaN : tps[2], mspt);

        List<RegionSample> samples = new CopyOnWriteArrayList<>();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        if (folia) {
            List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
            if (players.size() > cfg.lag.maxRegionSamples()) {
                players = new ArrayList<>(players.subList(0, cfg.lag.maxRegionSamples()));
            }
            for (Player player : players) {
                futures.add(ctx.scheduler().supplyOnEntity(player,
                                () -> sample(player))
                        .thenAccept(samples::add)
                        .exceptionally(error -> {
                            ctx.logger().debug("Lag sample for {} unavailable", player.getName(), error);
                            return null;
                        }));
            }
        }
        // On Folia, World#getEntityCount() and World#getPlayers() read region-owned state from the global
        // thread, so the world counts are simply omitted there instead of gambling on a torn read.
        CompletableFuture<List<WorldCounts>> worldsFuture = cfg.lag.includeWorldCounts && !folia
                ? ctx.scheduler().supplyGlobal(this::worldCounts)
                : CompletableFuture.completedFuture(List.of());
        futures.add(worldsFuture);

        CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        all.orTimeout(cfg.lag.timeoutSeconds(), TimeUnit.SECONDS).whenComplete((ignored, error) -> {
            boolean timedOut = error instanceof TimeoutException;
            if (error != null && !timedOut) {
                ctx.logger().warn("Lag report sampling failed", error);
            }
            List<WorldCounts> worlds = worldsFuture.isDone() && !worldsFuture.isCompletedExceptionally()
                    ? worldsFuture.getNow(List.of()) : List.of();
            deliver(sender, () -> sendReport(sender, cfg, folia, self, samples, worlds, timedOut));
        });
        return dev.yanianz.atrium.core.command.Cmds.OK;
    }

    private RegionSample sample(Player player) {
        double[] tps = tps();
        return new RegionSample(Thread.currentThread().getName(), player.getWorld().getName(), player.getName(),
                tps == null ? Double.NaN : tps[0],
                tps == null ? Double.NaN : tps[1],
                tps == null ? Double.NaN : tps[2], mspt());
    }

    private void sendReport(CommandSender sender, PerformanceConfig cfg, boolean folia, RegionSample self,
            List<RegionSample> samples, List<WorldCounts> worlds, boolean timedOut) {
        ctx.messages().send(sender, "performance.lag-header",
                Msg.unparsed("platform", folia ? "Folia" : "Paper"),
                Msg.unparsed("uptime", Durations.format(
                        Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime()), 3)),
                Msg.number("players", Bukkit.getOnlinePlayers().size()));
        sendServerMetrics(sender);

        if (!folia) {
            ctx.messages().send(sender, "performance.lag-tps-global",
                    Msg.unparsed("tps1", number(self.tps1())),
                    Msg.unparsed("tps5", number(self.tps5())),
                    Msg.unparsed("tps15", number(self.tps15())),
                    Msg.unparsed("mspt", number(self.mspt())));
        } else {
            if (Double.isFinite(self.tps1())) {
                String key = sender instanceof Player ? "performance.lag-region-self" : "performance.lag-global-region";
                ctx.messages().send(sender, key,
                        Msg.unparsed("tps1", number(self.tps1())),
                        Msg.unparsed("tps5", number(self.tps5())),
                        Msg.unparsed("tps15", number(self.tps15())),
                        Msg.unparsed("mspt", number(self.mspt())));
            }
            Map<String, RegionAgg> regions = new LinkedHashMap<>();
            for (RegionSample sample : samples) {
                RegionAgg region = regions.get(sample.thread());
                if (region == null) {
                    regions.put(sample.thread(), new RegionAgg(sample));
                } else {
                    region.add(sample);
                }
            }
            if (regions.isEmpty()) {
                ctx.messages().send(sender, "performance.lag-no-regions");
            } else {
                ctx.messages().send(sender, "performance.lag-region-header",
                        Msg.number("count", regions.size()));
                int shown = 0;
                for (RegionAgg region : regions.values()) {
                    if (shown++ >= cfg.lag.maxRegionLines()) {
                        break;
                    }
                    ctx.messages().send(sender, "performance.lag-region-line",
                            Msg.unparsed("region", region.label()),
                            Msg.unparsed("world", region.world == null ? "?" : region.world),
                            Msg.unparsed("tps1", number(region.tps1)),
                            Msg.unparsed("tps5", number(region.tps5)),
                            Msg.unparsed("tps15", number(region.tps15)),
                            Msg.unparsed("mspt", number(region.mspt)),
                            Msg.number("players", region.players));
                }
                if (regions.size() > cfg.lag.maxRegionLines()) {
                    ctx.messages().send(sender, "performance.lag-region-more",
                            Msg.number("count", regions.size() - cfg.lag.maxRegionLines()));
                }
                List<Double> values = new ArrayList<>(regions.size());
                for (RegionAgg region : regions.values()) {
                    if (Double.isFinite(region.tps1)) {
                        values.add(region.tps1);
                    }
                }
                TpsSummary.of(values).ifPresent(summary -> ctx.messages().send(sender,
                        "performance.lag-region-summary",
                        Msg.number("regions", summary.regions()),
                        Msg.unparsed("min", number(summary.min())),
                        Msg.unparsed("avg", number(summary.average())),
                        Msg.unparsed("max", number(summary.max()))));
            }
        }

        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L;
        long max = runtime.maxMemory() / 1_048_576L;
        long free = runtime.freeMemory() / 1_048_576L;
        double percent = max <= 0 ? 0 : used * 100.0 / max;
        ctx.messages().send(sender, "performance.lag-memory",
                Msg.number("used", used), Msg.number("max", max), Msg.number("free", free),
                Msg.unparsed("percent", String.format(Locale.ROOT, "%.1f", percent)));

        if (cfg.lag.includeWorldCounts) {
            if (folia) {
                // Entity and player counts live in the regions on Folia; only the global thread would
                // touch them here, so they are reported as unavailable rather than read unsafely.
                ctx.messages().send(sender, "performance.lag-worlds-unsupported");
            } else if (worlds.isEmpty()) {
                ctx.messages().send(sender, "performance.lag-worlds-empty");
            } else {
                ctx.messages().send(sender, "performance.lag-world-header");
                for (WorldCounts world : worlds) {
                    ctx.messages().send(sender, "performance.lag-world-line",
                            Msg.unparsed("world", world.world()),
                            Msg.number("entities", world.entities()),
                            Msg.number("chunks", world.chunks()),
                            Msg.number("players", world.players()));
                }
            }
        }
        if (cfg.entityLimits.enabled) {
            EntityLimitStats.Snapshot stats = limits.stats();
            ctx.messages().send(sender, "performance.lag-entity",
                    Msg.number("denied", stats.total()),
                    Msg.number("animals", stats.animals()),
                    Msg.number("monsters", stats.monsters()),
                    Msg.number("items", stats.items()),
                    Msg.number("minecarts", stats.minecarts()));
        } else {
            ctx.messages().send(sender, "performance.lag-entity-disabled");
        }
        if (timedOut) {
            ctx.messages().send(sender, "performance.lag-timeout");
        }
    }

    /**
     * Server-measured region metrics (SourbyCraft's metrics runtime through the sourbycraft module), when
     * published. The service is thread-safe and never blocks; without it nothing is printed.
     */
    private void sendServerMetrics(CommandSender sender) {
        var service = ctx.services().get(dev.yanianz.atrium.api.performance.RegionMetricsService.class);
        if (service.isEmpty()) {
            return;
        }
        java.util.Optional<dev.yanianz.atrium.api.performance.RegionMetricsService.Summary> summary;
        try {
            summary = service.get().summary();
        } catch (RuntimeException e) {
            ctx.logger().debug("Server region metrics unavailable", e);
            return;
        }
        summary.ifPresent(s -> ctx.messages().send(sender, "performance.lag-server-metrics",
                Msg.unparsed("source", s.source()),
                Msg.number("regions", s.activeRegions()),
                Msg.unparsed("worst_tps", number(s.worstTps())),
                Msg.unparsed("median_tps", number(s.medianTps())),
                Msg.unparsed("avg_mspt", number(s.averageMspt())),
                Msg.unparsed("p95_mspt", number(s.p95Mspt())),
                Msg.unparsed("max_mspt", number(s.maxMspt())),
                Msg.unparsed("global_tps", number(s.globalTps())),
                Msg.unparsed("freshness", s.freshness())));
    }

    private List<WorldCounts> worldCounts() {
        List<WorldCounts> result = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            try {
                result.add(new WorldCounts(world.getName(), world.getEntityCount(), world.getChunkCount(),
                        world.getPlayers().size()));
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot read entity/chunk counts for world {}", world.getName(), e);
            }
        }
        return List.copyOf(result);
    }

    /** Paper returns server TPS; on Folia the same call is region-scoped on a region thread. */
    private double[] tps() {
        try {
            double[] tps = Bukkit.getServer().getTPS();
            return tps != null && tps.length >= 3 ? tps : null;
        } catch (UnsupportedOperationException | IllegalStateException e) {
            // Expected on the Folia global thread, where no region TPS exists.
            return null;
        } catch (RuntimeException e) {
            ctx.logger().debug("TPS sample unavailable", e);
            return null;
        }
    }

    private double mspt() {
        try {
            return Bukkit.getServer().getAverageTickTime();
        } catch (UnsupportedOperationException | IllegalStateException e) {
            return Double.NaN;
        } catch (RuntimeException e) {
            ctx.logger().debug("MSPT sample unavailable", e);
            return Double.NaN;
        }
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) {
            return "?";
        }
        return String.format(Locale.ROOT, "%.2f", Math.max(0, Math.min(20_000, value)));
    }

    private void deliver(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            if (player.isOnline()) {
                ctx.scheduler().entity(player, () -> {
                    if (player.isOnline()) {
                        action.run();
                    }
                }, null);
            }
        } else {
            action.run();
        }
    }
}
