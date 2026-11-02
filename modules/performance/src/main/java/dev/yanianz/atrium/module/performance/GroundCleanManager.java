package dev.yanianz.atrium.module.performance;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

/**
 * Countdown, region-scoped execution and audit trail of ground cleanups.
 *
 * <p>No world is enumerated from one thread: each online player's region scans only the loaded
 * chunks it currently owns, and a chunk is claimed once across all players. No chunk is loaded.
 */
final class GroundCleanManager {
    enum StartResult { STARTED, BUSY, UNKNOWN_WORLD, NO_PLAYERS }

    enum Trigger { COMMAND, SCHEDULE }

    record ProtectZone(UUID world, double x, double y, double z) {}

    record ChunkTotals(int chunks, int items, int orbs, int whitelisted, int protectedCount, int young,
            int skipped, int failed) {
        static final ChunkTotals EMPTY = new ChunkTotals(0, 0, 0, 0, 0, 0, 0, 0);

        ChunkTotals plus(ChunkTotals other) {
            return new ChunkTotals(chunks + other.chunks, items + other.items, orbs + other.orbs,
                    whitelisted + other.whitelisted, protectedCount + other.protectedCount,
                    young + other.young, skipped + other.skipped, failed + other.failed);
        }
    }

    private final ModuleContext ctx;
    private final Supplier<PerformanceConfig> config;
    private final GroundCleanStore store;
    private final AtomicReference<Run> active = new AtomicReference<>();
    private volatile GroundCleanFilter filter;
    private volatile boolean stopped;

    GroundCleanManager(ModuleContext ctx, Supplier<PerformanceConfig> config, GroundCleanStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.filter = GroundCleanFilter.from(config.get().groundClean);
    }

    void reload() {
        filter = GroundCleanFilter.from(config.get().groundClean);
    }

    boolean isActive() {
        return active.get() != null;
    }

    StartResult start(CommandSender actor, String worldName, boolean silent, int leadSeconds, Trigger trigger) {
        if (stopped) {
            return StartResult.BUSY;
        }
        World world = null;
        if (worldName != null && !worldName.isBlank()) {
            world = Bukkit.getWorld(worldName);
            if (world == null) {
                return StartResult.UNKNOWN_WORLD;
            }
        }
        if (Bukkit.getOnlinePlayers().isEmpty()) {
            return StartResult.NO_PLAYERS;
        }
        Run run = new Run(actor, world, silent, Math.max(0, leadSeconds), trigger);
        if (!active.compareAndSet(null, run)) {
            return StartResult.BUSY;
        }
        run.schedule();
        return StartResult.STARTED;
    }

    /** Returns true when something was pending or running. */
    boolean cancel() {
        Run run = active.get();
        if (run == null) {
            return false;
        }
        boolean wasRunning = run.cancel();
        if (wasRunning) {
            send(run, "performance.groundclean-cancelling");
        } else {
            send(run, "performance.groundclean-cancelled", Msg.unparsed("world", run.worldLabel));
        }
        return true;
    }

    void shutdown() {
        stopped = true;
        Run run = active.getAndSet(null);
        if (run != null) {
            run.cancelTasks();
        }
    }

    private static String worldLabel(World world) {
        return world == null ? "*" : world.getName();
    }

    private final class Run {
        private final CommandSender actor;
        private final World world;
        private final boolean silent;
        private final int leadSeconds;
        private final Trigger trigger;
        private final String worldLabel;
        private final List<TaskHandle> tasks = new CopyOnWriteArrayList<>();
        private final AtomicBoolean reported = new AtomicBoolean();
        private volatile boolean cancelled;
        private volatile boolean running;
        private volatile boolean stopScanning;
        private volatile boolean finished;
        private long startedAt;

        Run(CommandSender actor, World world, boolean silent, int leadSeconds, Trigger trigger) {
            this.actor = actor;
            this.world = world;
            this.silent = silent;
            this.leadSeconds = leadSeconds;
            this.trigger = trigger;
            this.worldLabel = GroundCleanManager.worldLabel(world);
        }

        void schedule() {
            PerformanceConfig.GroundClean cfg = config.get().groundClean;
            if (leadSeconds <= 0) {
                send(this, "performance.groundclean-started-now",
                        Msg.unparsed("world", worldLabel), Msg.number("age", cfg.minAgeSeconds()),
                        Msg.number("radius", cfg.radiusChunks()));
            } else {
                send(this, "performance.groundclean-started",
                        Msg.unparsed("world", worldLabel), Msg.number("seconds", leadSeconds),
                        Msg.number("age", cfg.minAgeSeconds()), Msg.number("radius", cfg.radiusChunks()));
            }
            if (!silent) {
                for (int mark : GroundCleanCountdown.warnings(leadSeconds, cfg.warningSeconds)) {
                    long delay = Math.max(0, (leadSeconds - mark) * 20L);
                    addTask(ctx.track(ctx.scheduler().globalLater(() -> {
                        if (!cancelled && !stopped) {
                            broadcast("performance.groundclean-warning", Msg.number("seconds", mark));
                        }
                    }, delay)));
                }
            }
            addTask(ctx.track(ctx.scheduler().globalLater(this::execute, Math.max(0, leadSeconds) * 20L)));
        }

        private void addTask(TaskHandle handle) {
            tasks.add(handle);
            if (cancelled) {
                handle.cancel();
            }
        }

        boolean cancel() {
            if (finished) {
                return false;
            }
            cancelled = true;
            stopScanning = true;
            boolean wasRunning = running;
            if (!wasRunning) {
                cancelTasks();
                active.compareAndSet(this, null);
                finished = true;
                reported.set(true);
            }
            return wasRunning;
        }

        void cancelTasks() {
            for (TaskHandle task : tasks) {
                task.cancel();
            }
            tasks.clear();
        }

        private void execute() {
            if (stopped || cancelled) {
                finishReport(ChunkTotals.EMPTY, true, false, 0);
                return;
            }
            running = true;
            startedAt = System.nanoTime();
            PerformanceConfig cfg = config.get();
            List<Player> allPlayers = new ArrayList<>(Bukkit.getOnlinePlayers());
            int cap = cfg.groundClean.maxPlayersPerRun();
            boolean capped = allPlayers.size() > cap;
            final List<Player> selected = capped
                    ? new ArrayList<>(allPlayers.subList(0, cap))
                    : new ArrayList<>(allPlayers);
            if (selected.isEmpty()) {
                finishReport(ChunkTotals.EMPTY, false, capped, 0);
                return;
            }

            List<ProtectZone> zones = new CopyOnWriteArrayList<>();
            List<CompletableFuture<?>> zoneFutures = new ArrayList<>();
            int protectRadius = cfg.groundClean.protectRadius();
            // Protection applies around every online player, including players beyond the cleanup
            // cap, so a protected drop is never deleted because its owner was not a scan centre.
            for (Player player : allPlayers) {
                CompletableFuture<?> zoneFuture;
                try {
                    zoneFuture = ctx.scheduler().supplyOnEntity(player, () -> {
                        if (protectRadius <= 0 || !player.hasPermission("atrium.groundclean.protect")) {
                            return null;
                        }
                        // Do not collect a zone in another world; the world filter is applied here so
                        // the player's own world is only read on the player's region thread.
                        if (world != null && !player.getWorld().equals(world)) {
                            return null;
                        }
                        Location at = player.getLocation();
                        return new ProtectZone(at.getWorld().getUID(), at.getX(), at.getY(), at.getZ());
                    }).thenAccept(zone -> {
                        if (zone != null) {
                            zones.add(zone);
                        }
                    }).exceptionally(error -> {
                        if (!(error instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)
                                && !(error.getCause() instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)) {
                            ctx.logger().warn("Ground cleanup: cannot read the protection zone of {}",
                                    player.getName(), error);
                        }
                        return null;
                    });
                } catch (RuntimeException error) {
                    ctx.logger().warn("Ground cleanup: cannot schedule the protection zone of {}",
                            player.getName(), error);
                    zoneFuture = CompletableFuture.failedFuture(error);
                }
                zoneFutures.add(zoneFuture);
            }

            int playerCount = selected.size();
            CompletableFuture<Void> zonesReady = CompletableFuture.allOf(zoneFutures.toArray(CompletableFuture[]::new));
            zonesReady.orTimeout(cfg.groundClean.protectionTimeoutSeconds(), TimeUnit.SECONDS)
                    .whenComplete((ignored, error) -> {
                        if (stopped || cancelled) {
                            finishReport(ChunkTotals.EMPTY, true, capped, playerCount);
                            return;
                        }
                        if (error != null) {
                            ctx.logger().warn("Ground cleanup aborted: protection zones were not available in time", error);
                            send(this, "performance.groundclean-protection-failed");
                            finishReport(ChunkTotals.EMPTY, false, capped, playerCount);
                            return;
                        }
                        clean(selected, zones, cfg, capped, playerCount);
                    });
        }

        private void clean(List<Player> players, List<ProtectZone> zones, PerformanceConfig cfg,
                boolean capped, int playerCount) {
            GroundCleanFilter snapshot = filter;
            Set<String> seen = ConcurrentHashMap.newKeySet();
            int radius = cfg.groundClean.radiusChunks();
            long minAge = cfg.groundClean.minAgeTicks();
            boolean items = cfg.groundClean.removeItems;
            boolean orbs = cfg.groundClean.removeExperienceOrbs;
            double protectRadius = cfg.groundClean.protectRadius();
            List<CompletableFuture<ChunkTotals>> futures = new ArrayList<>(players.size());
            for (Player player : players) {
                CompletableFuture<ChunkTotals> future;
                try {
                    future = ctx.scheduler().supplyOnEntity(player,
                                    () -> cleanAround(player, snapshot, zones, seen, radius, minAge, items, orbs,
                                            protectRadius))
                            .exceptionally(error -> {
                                if (!(error instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)
                                        && !(error.getCause() instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)) {
                                    ctx.logger().warn("Ground cleanup around {} failed", player.getName(), error);
                                    return new ChunkTotals(0, 0, 0, 0, 0, 0, 0, 1);
                                }
                                return ChunkTotals.EMPTY;
                            });
                } catch (RuntimeException error) {
                    ctx.logger().warn("Ground cleanup: cannot schedule the region of {}", player.getName(), error);
                    future = CompletableFuture.completedFuture(new ChunkTotals(0, 0, 0, 0, 0, 0, 0, 1));
                }
                futures.add(future);
            }
            CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
            all.orTimeout(cfg.groundClean.runTimeoutSeconds(), TimeUnit.SECONDS).whenComplete((ignored, error) -> {
                boolean timedOut = error instanceof TimeoutException;
                boolean partial = error != null;
                if (error != null && !timedOut) {
                    ctx.logger().warn("Ground cleanup tasks did not complete normally", error);
                }
                if (partial) {
                    stopScanning = true;
                }
                ChunkTotals totals = ChunkTotals.EMPTY;
                for (CompletableFuture<ChunkTotals> future : futures) {
                    if (future.isDone() && !future.isCompletedExceptionally()) {
                        try {
                            totals = totals.plus(future.getNow(ChunkTotals.EMPTY));
                        } catch (RuntimeException e) {
                            ctx.logger().debug("Ground cleanup partial result unavailable", e);
                        }
                    }
                }
                finishReport(totals, cancelled, capped, playerCount, partial);
            });
        }

        /** Runs on one player's region thread; returns only chunks that region currently owns. */
        private ChunkTotals cleanAround(Player player, GroundCleanFilter filter, List<ProtectZone> zones,
                Set<String> seen, int radius, long minAge, boolean removeItems, boolean removeOrbs,
                double protectRadius) {
            World regionWorld = player.getWorld();
            if (world != null && !regionWorld.equals(world)) {
                return ChunkTotals.EMPTY;
            }
            Location at = player.getLocation();
            int centerX = at.getBlockX() >> 4;
            int centerZ = at.getBlockZ() >> 4;
            int chunks = 0;
            int items = 0;
            int orbs = 0;
            int whitelisted = 0;
            int protectedCount = 0;
            int young = 0;
            int skipped = 0;
            int failed = 0;
            double protectSq = protectRadius * protectRadius;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (stopScanning || stopped) {
                        return new ChunkTotals(chunks, items, orbs, whitelisted, protectedCount, young, skipped, failed);
                    }
                    int x = centerX + dx;
                    int z = centerZ + dz;
                    // Never load or generate terrain, and never touch a chunk another region owns.
                    if (!regionWorld.isChunkLoaded(x, z) || !Bukkit.isOwnedByCurrentRegion(regionWorld, x, z)) {
                        skipped++;
                        continue;
                    }
                    String key = regionWorld.getUID() + ":" + x + ":" + z;
                    if (!seen.add(key)) {
                        continue;
                    }
                    try {
                        Chunk chunk = regionWorld.getChunkAt(x, z);
                        if (!chunk.isEntitiesLoaded()) {
                            skipped++;
                            continue;
                        }
                        chunks++;
                        for (Entity entity : chunk.getEntities()) {
                            if (!entity.isValid()) {
                                continue;
                            }
                            if (entity instanceof Item item) {
                                if (!removeItems) {
                                    continue;
                                }
                                if (item.getTicksLived() < minAge) {
                                    young++;
                                    continue;
                                }
                                if (filter.keeps(item)) {
                                    whitelisted++;
                                    continue;
                                }
                                if (protectRadius > 0 && protectedAt(item.getLocation(), zones, protectSq)) {
                                    protectedCount++;
                                    continue;
                                }
                                item.remove();
                                items++;
                            } else if (entity instanceof ExperienceOrb orb) {
                                if (!removeOrbs) {
                                    continue;
                                }
                                if (orb.getTicksLived() < minAge) {
                                    young++;
                                    continue;
                                }
                                if (protectRadius > 0 && protectedAt(orb.getLocation(), zones, protectSq)) {
                                    protectedCount++;
                                    continue;
                                }
                                orb.remove();
                                orbs++;
                            }
                        }
                    } catch (RuntimeException e) {
                        failed++;
                        ctx.logger().warn("Ground cleanup could not scan chunk {},{} in {}",
                                x, z, regionWorld.getName(), e);
                    }
                }
            }
            return new ChunkTotals(chunks, items, orbs, whitelisted, protectedCount, young, skipped, failed);
        }

        private void finishReport(ChunkTotals totals, boolean wasCancelled, boolean capped, int playerCount) {
            finishReport(totals, wasCancelled, capped, playerCount, false);
        }

        private void finishReport(ChunkTotals totals, boolean wasCancelled, boolean capped, int playerCount,
                boolean timedOut) {
            if (!reported.compareAndSet(false, true)) {
                return;
            }
            finished = true;
            cancelTasks();
            active.compareAndSet(this, null);
            if (stopped) {
                return;
            }
            long durationMs = startedAt == 0 ? 0 : Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
            String key = wasCancelled ? "performance.groundclean-cancelled-summary"
                    : timedOut ? "performance.groundclean-partial" : "performance.groundclean-done";
            send(this, key,
                    Msg.unparsed("world", worldLabel),
                    Msg.number("items", totals.items()),
                    Msg.number("orbs", totals.orbs()),
                    Msg.number("whitelisted", totals.whitelisted()),
                    Msg.number("protected", totals.protectedCount()),
                    Msg.number("young", totals.young()),
                    Msg.number("chunks", totals.chunks()),
                    Msg.number("seconds", durationMs / 1000.0),
                    Msg.number("players", playerCount));
            if (capped) {
                send(this, "performance.groundclean-capped",
                        Msg.number("max", config.get().groundClean.maxPlayersPerRun()));
            }
            if (!wasCancelled && !timedOut && !silent) {
                broadcast("performance.groundclean-broadcast",
                        Msg.number("items", totals.items()), Msg.number("orbs", totals.orbs()));
            }
            ctx.logger().info("Ground cleanup ({}): removed {} item(s) and {} orb(s) in {} loaded chunk(s); "
                            + "{} whitelisted, {} protected, {} too young, {} failed, {} ms",
                    trigger, totals.items(), totals.orbs(), totals.chunks(), totals.whitelisted(),
                    totals.protectedCount(), totals.young(), totals.failed(), durationMs);
            if (!wasCancelled && !timedOut) {
                GroundCleanStore.RunRecord record = new GroundCleanStore.RunRecord(GroundCleanStore.newId(),
                        System.currentTimeMillis(), trigger.name(), actorName(), worldLabel, totals.items(),
                        totals.orbs(), totals.whitelisted(), totals.protectedCount(), totals.chunks(), durationMs);
                store.record(record).exceptionally(error -> {
                    ctx.logger().error("Cannot store the ground cleanup history entry", error);
                    return null;
                });
            }
        }

        private String actorName() {
            if (actor instanceof Player player) {
                return player.getName();
            }
            return "CONSOLE";
        }
    }

    private void send(Run run, String key, TagResolver... tags) {
        if (stopped) {
            return;
        }
        CommandSender actor = run.actor;
        if (actor instanceof Player player) {
            if (player.isOnline()) {
                ctx.scheduler().entity(player, () -> {
                    if (player.isOnline() && !stopped) {
                        ctx.messages().send(player, key, tags);
                    }
                }, null);
            }
        } else {
            ctx.messages().send(actor, key, tags);
        }
    }

    private void broadcast(String key, TagResolver... tags) {
        if (stopped) {
            return;
        }
        ctx.scheduler().global(() -> {
            if (stopped) {
                return;
            }
            // One task per recipient keeps each player's client locale and avoids touching a
            // player from the global region thread.
            for (Player player : Bukkit.getOnlinePlayers()) {
                ctx.scheduler().entity(player, () -> {
                    if (player.isOnline() && !stopped) {
                        ctx.messages().send(player, key, tags);
                    }
                }, null);
            }
        });
    }

    private static boolean protectedAt(Location location, List<ProtectZone> zones, double radiusSq) {
        UUID world = location.getWorld() == null ? null : location.getWorld().getUID();
        if (world == null) {
            return false;
        }
        for (ProtectZone zone : zones) {
            if (!zone.world().equals(world)) {
                continue;
            }
            double dx = location.getX() - zone.x();
            double dy = location.getY() - zone.y();
            double dz = location.getZ() - zone.z();
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq <= radiusSq) {
                return true;
            }
        }
        return false;
    }
}
