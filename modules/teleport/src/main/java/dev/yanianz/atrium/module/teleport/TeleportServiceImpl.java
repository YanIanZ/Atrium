package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.AtriumPreTeleportEvent;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.BoundingBox;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The teleport pipeline. Order for every teleport, all on the player's own thread:
 * <ol>
 *   <li>cooldown check (no side effects),</li>
 *   <li>{@link AtriumPreTeleportEvent} (cancellable, before anything changes),</li>
 *   <li>warmup (cancelled by movement, damage, another teleport or quitting),</li>
 *   <li>safe-spot search on the destination's region thread,</li>
 *   <li>{@code teleportAsync},</li>
 *   <li>only after it succeeded: /back location, cooldown and damage immunity.</li>
 * </ol>
 */
final class TeleportServiceImpl implements ExtendedTeleportService, Listener {
    static final String PERM_WARMUP_BYPASS = "atrium.teleport.warmup.bypass";
    static final String PERM_COOLDOWN_BYPASS = "atrium.teleport.cooldown.bypass";
    static final String PERM_BACK_ON_DEATH = "atrium.back.ondeath";
    static final String PERM_TPBYPASS = "atrium.tpbypass";
    static final String PERM_VEHICLE = "atrium.teleport.vehicle";

    private final ModuleContext ctx;
    private final Scheduler scheduler;
    private final Messages messages;
    private final Logger logger;
    private final ConfigHandle<TeleportConfig> config;
    private final TeleportStore store;
    private final SafeLanding landing;
    private final Cooldowns cooldowns;
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, Long> invulnerableUntil = new ConcurrentHashMap<>();
    private final Map<UUID, FailedSpot> failedSpots = new ConcurrentHashMap<>();
    private final Map<String, StoredLocation> spawns = new ConcurrentHashMap<>();
    /** Teleports this module started: the Bukkit listener must not record them in /back a second time. */
    private final StartedTeleports startedTeleports = new StartedTeleports();

    /** The exact destination of a teleport that failed because it was unsafe, for /tpbypass. */
    private record FailedSpot(Location location, long expiresAt) {}

    private static final class Warmup {
        final CompletableFuture<TeleportResult> result;
        final Runnable proceed;
        volatile TaskHandle task = TaskHandle.NOOP;
        /** Destination world name, so a world unload can cancel warmups into it. */
        volatile @Nullable String world;
        int remaining;

        Warmup(CompletableFuture<TeleportResult> result, int seconds, Runnable proceed) {
            this.result = result;
            this.remaining = seconds;
            this.proceed = proceed;
        }
    }

    TeleportServiceImpl(ModuleContext ctx, ConfigHandle<TeleportConfig> config, TeleportStore store) {
        this(ctx, config, store, Clock.systemUTC());
    }

    TeleportServiceImpl(ModuleContext ctx, ConfigHandle<TeleportConfig> config, TeleportStore store, Clock clock) {
        this.ctx = ctx;
        this.cooldowns = new Cooldowns(clock);
        this.scheduler = ctx.scheduler();
        this.messages = ctx.messages();
        this.logger = ctx.logger();
        this.config = config;
        this.store = store;
        this.landing = new SafeLanding(scheduler);
    }

    SafeLanding landing() {
        return landing;
    }

    TeleportStore store() {
        return store;
    }

    // ---- TeleportService ----

    @Override
    public CompletableFuture<TeleportResult> teleport(Player player, Location target, TeleportCause cause) {
        return teleport(player, target, cause, TeleportOptions.DEFAULT);
    }

    @Override
    public CompletableFuture<TeleportResult> teleportNow(Player player, Location target, TeleportCause cause) {
        return teleport(player, target, cause, TeleportOptions.IMMEDIATE);
    }

    @Override
    public CompletableFuture<TeleportResult> teleport(Player player, Location target, TeleportCause cause,
            TeleportOptions options) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(cause, "cause");
        TeleportOptions opts = options == null ? TeleportOptions.DEFAULT : options;
        if (target.getWorld() == null) {
            return CompletableFuture.completedFuture(TeleportResult.FAILED);
        }
        Location destination = target.clone();
        CompletableFuture<TeleportResult> result = new CompletableFuture<>();
        scheduler.entity(player, () -> {
            try {
                begin(player, destination, cause, opts, result);
            } catch (RuntimeException e) {
                fail(player, result, e, "starting");
            }
        }, () -> result.complete(TeleportResult.FAILED));
        return result;
    }

    @Override
    public Optional<Location> backLocation(Player player) {
        return backHistory(player.getUniqueId()).latest().flatMap(e -> e.location().toLocation());
    }

    /** The loaded player's /back history without expired entries (empty when not loaded). */
    BackHistory backHistory(UUID player) {
        return ctx.users().cached(player).map(this::historyOf).orElse(BackHistory.EMPTY);
    }

    /** /back history of an already loaded user (also works for a player loaded from storage and offline). */
    BackHistory historyOf(AtriumUser user) {
        Objects.requireNonNull(user, "user");
        BackHistory history;
        try {
            history = user.get(store.component).back();
        } catch (IllegalStateException e) {
            return BackHistory.EMPTY;
        }
        TeleportConfig.Back cfg = config.get().back;
        history = history.trim(cfg.historySize);
        if (cfg.expireMinutes > 0) {
            history = history.recordedSince(System.currentTimeMillis() - Duration.ofMinutes(cfg.expireMinutes).toMillis());
        }
        return history;
    }

    /** Removes a /back entry that was just used. */
    void forgetBack(UUID player, BackHistory.Entry entry) {
        ctx.users().cached(player).ifPresent(user -> {
            try {
                user.update(store.component, s -> s.withBack(s.back().without(entry)));
            } catch (IllegalStateException e) {
                logger.warn("Cannot update /back history of {}: teleport data not loaded", player);
            }
        });
    }

    /**
     * Forgets the whole /back history of a player (/resetback). Completes with {@code false} when there was
     * nothing to forget; a loaded user is written through before the future completes.
     */
    CompletableFuture<Boolean> clearBack(UUID player) {
        Optional<AtriumUser> loaded = ctx.users().cached(player);
        if (loaded.isPresent()) {
            try {
                if (loaded.get().get(store.component).back().isEmpty()) {
                    return CompletableFuture.completedFuture(false);
                }
                loaded.get().update(store.component, s -> s.withBack(BackHistory.EMPTY));
                return ctx.users().saveNow(player).thenApply(ignored -> true);
            } catch (IllegalStateException e) {
                logger.warn("Cannot clear the /back history of {}: teleport data not loaded", player, e);
                return CompletableFuture.failedFuture(e);
            }
        }
        return store.load(player).thenCompose(stored -> {
            TeleportState state = stored == null ? TeleportState.DEFAULT : stored;
            if (state.back().isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            return store.save(player, state.withBack(BackHistory.EMPTY)).thenApply(ignored -> true);
        });
    }

    Optional<TeleportState> state(Player player) {
        return ctx.users().cached(player.getUniqueId()).flatMap(user -> {
            try {
                return Optional.of(user.get(store.component));
            } catch (IllegalStateException e) {
                return Optional.empty();
            }
        });
    }

    @Override
    public boolean hasPendingTeleport(Player player) {
        return warmups.containsKey(player.getUniqueId());
    }

    // ---- cooldowns ----

    /** Restores the cooldowns stored before the last shutdown. */
    void loadCooldowns(List<TeleportStore.CooldownRow> rows) {
        for (TeleportStore.CooldownRow row : rows) {
            cooldowns.restore(row.player(), row.cause(), Instant.ofEpochMilli(row.expiresAt()));
        }
    }

    /** Starts a cooldown and writes it through to storage (a failed write is logged; memory stays authoritative). */
    private void startCooldown(UUID uuid, TeleportCause cause, Duration length) {
        Instant end = cooldowns.start(uuid, cause, length);
        CompletableFuture<Void> write = end == null
                ? store.deleteCooldown(uuid, cause)
                : store.saveCooldown(uuid, cause, end.toEpochMilli());
        write.exceptionally(error -> {
            logger.error("Could not store the {} teleport cooldown of {}", cause, uuid, error);
            return null;
        });
    }

    // ---- spawns ----

    void loadSpawns(Map<String, StoredLocation> loaded) {
        spawns.clear();
        spawns.putAll(loaded);
    }

    Optional<StoredLocation> spawn(String id) {
        return Optional.ofNullable(spawns.get(id));
    }

    CompletableFuture<Void> setSpawn(String id, StoredLocation location) {
        return store.saveSpawn(id, location).thenRun(() -> spawns.put(id, location));
    }

    CompletableFuture<Boolean> deleteSpawn(String id) {
        return store.deleteSpawn(id).thenApply(deleted -> {
            spawns.remove(id);
            return deleted;
        });
    }

    /** A copy of every stored spawn point by id. */
    Map<String, StoredLocation> spawnsSnapshot() {
        return Map.copyOf(spawns);
    }

    /** The spawn {@code /spawn} gives this player in this world (see {@link SpawnResolver}). */
    SpawnResolver.Choice spawnChoice(@Nullable Player player, @Nullable World world) {
        TeleportConfig cfg = config.get();
        return SpawnResolver.forPlayer(spawns, cfg.spawn.perWorldSpawns, cfg.spawn.groupSpawns,
                world == null ? null : world.getName(),
                group -> player != null && inGroup(player, group), cfg.spawn.groupOrder);
    }

    /**
     * Group membership for group spawns: {@code atrium.spawn.group.<group>} must be set for the player (an
     * operator's implicit "every permission" does not count, or every operator would match every group).
     */
    static boolean inGroup(Player player, String group) {
        String node = SpawnResolver.groupPermission(group);
        return player.isPermissionSet(node) && player.hasPermission(node);
    }

    /** The vanilla fallback of a {@link SpawnResolver.Source#VANILLA} choice. */
    static Optional<Location> vanillaSpawn(@Nullable World world) {
        if (world != null) {
            return Optional.of(world.getSpawnLocation());
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? Optional.empty() : Optional.of(worlds.getFirst().getSpawnLocation());
    }

    @Override
    public Optional<Location> spawnLocation(@Nullable World world) {
        TeleportConfig cfg = config.get();
        SpawnResolver.Choice choice = spawnChoice(null, world);
        if (choice.stored()) {
            Optional<Location> loaded = choice.location().toLocation();
            if (loaded.isPresent()) {
                return loaded;
            }
        }
        return vanillaSpawn(cfg.spawn.perWorldSpawns ? world : null);
    }

    /** The stored spawn of the respawn's {@code spawn} token for this player; the vanilla fallback is left out. */
    Optional<StoredLocation> storedSpawn(@Nullable Player player, @Nullable World world) {
        SpawnResolver.Choice choice = spawnChoice(player, world);
        return Optional.ofNullable(choice.location());
    }

    /** First-join spawn, falling back to the main spawn; empty when neither is set. */
    Optional<StoredLocation> firstJoinStored() {
        return Optional.ofNullable(SpawnResolver.forFirstJoin(spawns).location());
    }

    // ---- pipeline ----

    private void begin(Player player, Location destination, TeleportCause cause, TeleportOptions opts,
            CompletableFuture<TeleportResult> result) {
        if (!player.isOnline()) {
            result.complete(TeleportResult.FAILED);
            return;
        }
        TeleportConfig cfg = config.get();
        UUID uuid = player.getUniqueId();
        if (opts.cooldown() && !player.hasPermission(PERM_COOLDOWN_BYPASS)) {
            Duration left = cooldowns.remaining(uuid, cause);
            if (!left.isZero()) {
                messages.send(player, "teleport.on-cooldown", Msg.duration("time", roundUp(left)));
                result.complete(TeleportResult.ON_COOLDOWN);
                return;
            }
        }
        AtriumPreTeleportEvent event = new AtriumPreTeleportEvent(player, player.getLocation(), destination, cause);
        if (!event.callEvent()) {
            result.complete(TeleportResult.CANCELLED_EVENT);
            return;
        }
        Location to = event.getTo();
        int seconds = opts.warmup() && !player.hasPermission(PERM_WARMUP_BYPASS) ? cfg.warmup(cause) : 0;
        if (seconds <= 0) {
            execute(player, to, cause, opts, result);
            return;
        }
        Warmup warmup = new Warmup(result, seconds, () -> execute(player, to, cause, opts, result));
        warmup.world = to.getWorld() == null ? null : to.getWorld().getName();
        Warmup previous = warmups.put(uuid, warmup);
        if (previous != null) {
            previous.task.cancel();
            messages.send(player, "teleport.warmup-replaced");
            previous.result.complete(TeleportResult.CANCELLED_EVENT);
        }
        messages.send(player, "teleport.warmup-started", Msg.number("seconds", seconds));
        if (cfg.warmupActionBar) {
            messages.actionBar(player, "teleport.warmup-countdown", Msg.number("seconds", seconds));
        }
        warmup.task = scheduler.entityTimer(player, () -> tick(player, warmup),
                () -> abort(uuid, warmup, TeleportResult.FAILED, null, null), 20L, 20L);
    }

    private void tick(Player player, Warmup warmup) {
        UUID uuid = player.getUniqueId();
        if (warmups.get(uuid) != warmup) {
            warmup.task.cancel();
            return;
        }
        warmup.remaining--;
        if (warmup.remaining > 0) {
            if (config.get().warmupActionBar) {
                messages.actionBar(player, "teleport.warmup-countdown", Msg.number("seconds", warmup.remaining));
            }
            return;
        }
        if (warmups.remove(uuid, warmup)) {
            warmup.task.cancel();
            try {
                warmup.proceed.run();
            } catch (RuntimeException e) {
                fail(player, warmup.result, e, "after warmup");
            }
        }
    }

    private void abort(UUID uuid, Warmup warmup, TeleportResult outcome, @Nullable Player player, @Nullable String key) {
        if (warmups.remove(uuid, warmup)) {
            warmup.task.cancel();
            if (player != null && key != null) {
                messages.send(player, key);
            }
            warmup.result.complete(outcome);
        }
    }

    private void execute(Player player, Location to, TeleportCause cause, TeleportOptions opts,
            CompletableFuture<TeleportResult> result) {
        TeleportConfig cfg = config.get();
        boolean safe = switch (opts.safety()) {
            case CONFIG -> cfg.safeLanding.appliesTo(cause);
            case CHECK -> true;
            case SKIP -> false;
        };
        CompletableFuture<Optional<Location>> spot = safe
                ? landing.find(to, cfg.safeLanding)
                : CompletableFuture.completedFuture(Optional.of(to));
        spot.whenComplete((found, error) -> {
            if (error != null) {
                fail(player, result, error, "searching a safe spot at " + describe(to));
                return;
            }
            if (found.isEmpty() && !SafetyBypass.allows(player, cause)) {
                rememberFailed(player, to, cfg);
                messages.send(player, hasBypass(player, cfg) ? "teleport.unsafe-can-bypass" : "teleport.unsafe",
                        Msg.number("seconds", Math.max(0, cfg.safeLanding.bypassWindowSeconds)));
                result.complete(TeleportResult.UNSAFE);
                return;
            }
            // No safe spot but the cause is bypassed: use the exact destination.
            Location destination = found.orElseGet(to::clone);
            scheduler.entity(player, () -> {
                if (!player.isOnline()) {
                    result.complete(TeleportResult.FAILED);
                    return;
                }
                // /back must lead to the spot the player leaves: read on its own thread, right before the move.
                Location from = player.getLocation();
                UUID uuid = player.getUniqueId();
                Entity vehicle = carryVehicle(player, cfg);
                startedTeleports.begin(uuid);
                CompletableFuture<Boolean> move;
                try {
                    move = player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
                } catch (RuntimeException e) {
                    startedTeleports.end(uuid);
                    remount(vehicle, player);
                    fail(player, result, e, "teleporting to " + describe(destination));
                    return;
                }
                move.whenComplete((ok, teleportError) -> {
                    // The Bukkit event already ran and skipped this teleport, so only succeeded() records /back.
                    startedTeleports.end(uuid);
                    if (teleportError != null) {
                        remount(vehicle, player);
                        fail(player, result, teleportError, "teleporting to " + describe(destination));
                        return;
                    }
                    if (!Boolean.TRUE.equals(ok)) {
                        // Another plugin cancelled the Bukkit teleport event, or the player left.
                        remount(vehicle, player);
                        messages.send(player, "teleport.failed");
                        result.complete(TeleportResult.FAILED);
                        return;
                    }
                    succeeded(player, from, cause, opts);
                    bringVehicle(vehicle, destination, player);
                    result.complete(TeleportResult.SUCCESS);
                });
            }, () -> result.complete(TeleportResult.FAILED));
        });
    }

    private static boolean hasBypass(Player player, TeleportConfig cfg) {
        return cfg.safeLanding.bypassWindowSeconds > 0 && player.hasPermission(PERM_TPBYPASS);
    }

    /** Remembers the exact destination of an unsafe teleport so /tpbypass can force it for a short while. */
    private void rememberFailed(Player player, Location to, TeleportConfig cfg) {
        if (!hasBypass(player, cfg)) {
            return;
        }
        long window = cfg.safeLanding.bypassWindowSeconds * 1000L;
        failedSpots.put(player.getUniqueId(), new FailedSpot(to.clone(), System.currentTimeMillis() + window));
    }

    /** The destination of the last failed teleport, consumed once and only while the window is open. */
    Optional<Location> takeFailedSpot(UUID player) {
        FailedSpot spot = failedSpots.remove(player);
        if (spot == null || spot.expiresAt() < System.currentTimeMillis()) {
            return Optional.empty();
        }
        return Optional.of(spot.location());
    }

    /**
     * Dismounts the ridden vehicle when it may be carried through the teleport. Returns the vehicle, or
     * {@code null} when the player stays mounted (feature off, wrong type, no permission).
     */
    private @Nullable Entity carryVehicle(Player player, TeleportConfig cfg) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null || !cfg.vehicle.enabled || !player.hasPermission(PERM_VEHICLE)
                || !cfg.vehicle.allows(vehicle.getType().name())) {
            return null;
        }
        player.leaveVehicle();
        return vehicle;
    }

    /** Puts the player back on their vehicle after a failed teleport (on the vehicle's own thread). */
    private void remount(@Nullable Entity vehicle, Player player) {
        if (vehicle == null || !vehicle.isValid()) {
            return;
        }
        scheduler.entity(vehicle, () -> {
            if (vehicle.isValid() && player.isOnline() && player.getVehicle() == null) {
                vehicle.addPassenger(player);
            }
        }, null);
    }

    /** Moves a carried vehicle to the destination and remounts the player once it arrived. */
    private void bringVehicle(@Nullable Entity vehicle, Location destination, Player player) {
        if (vehicle == null || !vehicle.isValid()) {
            return;
        }
        scheduler.entity(vehicle, () -> {
            if (!vehicle.isValid()) {
                return;
            }
            vehicle.teleportAsync(destination).whenComplete((moved, error) -> {
                if (error != null || !Boolean.TRUE.equals(moved)) {
                    logger.warn("Could not move the vehicle of {} through the teleport", player.getName(), error);
                    return;
                }
                scheduler.entity(player, () -> {
                    if (player.isOnline() && player.getVehicle() == null && player.getWorld().equals(vehicle.getWorld())) {
                        vehicle.addPassenger(player);
                    }
                }, null);
            });
        }, null);
    }

    /** Side effects that only apply to a teleport that really happened (the only place that records it). */
    private void succeeded(Player player, Location from, TeleportCause cause, TeleportOptions opts) {
        TeleportConfig cfg = config.get();
        UUID uuid = player.getUniqueId();
        recordBack(player, from, false, true);
        if (opts.cooldown() && cfg.cooldown(cause) > 0) {
            startCooldown(uuid, cause, Duration.ofSeconds(cfg.cooldown(cause)));
        }
        if (cfg.invulnerabilitySeconds > 0) {
            invulnerableUntil.put(uuid, System.currentTimeMillis() + cfg.invulnerabilitySeconds * 1000L);
        }
    }

    private void fail(Player player, CompletableFuture<TeleportResult> result, Throwable error, String what) {
        logger.error("Teleport of {} failed while {}", player.getName(), what, error);
        messages.send(player, "teleport.failed");
        result.complete(TeleportResult.FAILED);
    }

    private static String describe(Location l) {
        return (l.getWorld() == null ? "?" : l.getWorld().getName()) + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    private static Duration roundUp(Duration d) {
        long millis = d.toMillis();
        return Duration.ofSeconds((millis + 999) / 1000);
    }

    // ---- /back recording ----

    /**
     * Remembers {@code from} as the newest /back location (the oldest one is dropped when the history is full).
     *
     * @param checkDistance skip when the player moved less than {@code back.min-distance} in the same world
     */
    void recordBack(Player player, Location from, boolean death, boolean checkDistance) {
        TeleportConfig.Back cfg = config.get().back;
        World world = from.getWorld();
        if (world == null || cfg.blacklistedWorlds.stream().anyMatch(w -> w.equalsIgnoreCase(world.getName()))) {
            return;
        }
        if (checkDistance && !death) {
            Location now = player.getLocation();
            if (world.equals(now.getWorld()) && now.distanceSquared(from) < cfg.minDistance * cfg.minDistance) {
                return;
            }
        }
        Optional<AtriumUser> user = ctx.users().cached(player.getUniqueId());
        if (user.isEmpty()) {
            return;
        }
        BackHistory.Entry entry = new BackHistory.Entry(StoredLocation.of(from), death, System.currentTimeMillis());
        int capacity = cfg.historySize;
        try {
            user.get().update(store.component, s -> s.withBack(s.back().push(entry, capacity)));
        } catch (IllegalStateException e) {
            logger.warn("Cannot record /back for {}: teleport data not loaded yet", player.getName());
        }
    }

    // ---- listeners ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBukkitTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup != null) {
            abort(player.getUniqueId(), warmup, TeleportResult.CANCELLED_MOVED, player, "teleport.warmup-cancelled-move");
        }
        if (startedTeleports.isStarted(player.getUniqueId())) {
            // A teleport of this module: succeeded() records it exactly once, after it really happened.
            return;
        }
        TeleportConfig.Back cfg = config.get().back;
        String cause = event.getCause().name();
        if (cfg.ignoredBukkitCauses.stream().anyMatch(c -> c.equalsIgnoreCase(cause))) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != null && from.getWorld().equals(to.getWorld())
                && from.distanceSquared(to) < cfg.minDistance * cfg.minDistance) {
            return;
        }
        recordBack(player, from, false, false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() || !config.get().cancelWarmupOnMove) {
            return;
        }
        Player player = event.getPlayer();
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup != null) {
            abort(player.getUniqueId(), warmup, TeleportResult.CANCELLED_MOVED, player, "teleport.warmup-cancelled-move");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageImmunity(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Long until = invulnerableUntil.get(player.getUniqueId());
        if (until == null) {
            return;
        }
        if (System.currentTimeMillis() < until) {
            event.setCancelled(true);
        } else {
            invulnerableUntil.remove(player.getUniqueId(), until);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || !config.get().cancelWarmupOnDamage) {
            return;
        }
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup != null) {
            abort(player.getUniqueId(), warmup, TeleportResult.CANCELLED_DAMAGED, player, "teleport.warmup-cancelled-damage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Warmup warmup = warmups.get(player.getUniqueId());
        if (warmup != null) {
            abort(player.getUniqueId(), warmup, TeleportResult.CANCELLED_DAMAGED, null, null);
        }
        if (config.get().back.recordDeath && player.hasPermission(PERM_BACK_ON_DEATH)) {
            recordBack(player, player.getLocation(), true, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Warmup warmup = warmups.get(uuid);
        if (warmup != null) {
            abort(uuid, warmup, TeleportResult.FAILED, null, null);
        }
        invulnerableUntil.remove(uuid);
        failedSpots.remove(uuid);
        startedTeleports.clear(uuid);
    }

    // ---- world unload (Bukkit, and Atrium's notification for SourbyCraft's async unload) ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent event) {
        cancelPendingInto(event.getWorld().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnloading(dev.yanianz.atrium.api.world.WorldUnloadingEvent event) {
        cancelPendingInto(event.worldName());
    }

    /**
     * Cancels every warmup whose destination is in {@code world}, with a message on the player's own thread.
     * Idempotent, so the Bukkit event and Atrium's notification may both fire. Teleports already past the
     * warmup (safe-spot search, teleportAsync) fail on their own and report "teleport failed".
     */
    int cancelPendingInto(String world) {
        int cancelled = 0;
        for (Map.Entry<UUID, Warmup> entry : List.copyOf(warmups.entrySet())) {
            Warmup warmup = entry.getValue();
            if (!world.equals(warmup.world)) {
                continue;
            }
            cancelled++;
            UUID uuid = entry.getKey();
            Player player = Bukkit.getPlayer(uuid);
            Runnable silent = () -> abort(uuid, warmup, TeleportResult.CANCELLED_EVENT, null, null);
            if (player == null) {
                silent.run();
                continue;
            }
            scheduler.entity(player, () -> {
                if (warmups.remove(uuid, warmup)) {
                    warmup.task.cancel();
                    messages.send(player, "teleport.warmup-cancelled-world-unload", Msg.unparsed("world", world));
                    warmup.result.complete(TeleportResult.CANCELLED_EVENT);
                }
            }, silent);
        }
        if (cancelled > 0) {
            logger.info("Cancelled {} pending teleport(s) into world {} because it is unloading", cancelled, world);
        }
        return cancelled;
    }

    /** Periodic cleanup of expired cooldowns and immunity entries. */
    void purge() {
        cooldowns.purge();
        long now = System.currentTimeMillis();
        invulnerableUntil.values().removeIf(until -> until <= now);
        failedSpots.values().removeIf(spot -> spot.expiresAt() <= now);
    }

    // ---- safe login ----

    /**
     * A player who joins inside blocks (a bed in a wall, a world reset) is moved to the nearest safe spot
     * after one tick. Runs only when the hitbox really collides with blocks, so ordinary logins are untouched.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        TeleportConfig.SafeLogin cfg = config.get().safeLogin;
        if (!cfg.enabled) {
            return;
        }
        Player player = event.getPlayer();
        scheduler.entityLater(player, () -> relocateIfStuck(player, cfg), null, 1L);
    }

    private void relocateIfStuck(Player player, TeleportConfig.SafeLogin cfg) {
        if (!player.isOnline()) {
            return;
        }
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null || !insideBlocks(world, at)) {
            return;
        }
        TeleportConfig.SafeLanding window = new TeleportConfig.SafeLanding();
        window.searchUp = Math.max(0, cfg.searchUp);
        window.searchDown = Math.max(0, cfg.searchDown);
        window.searchRadius = Math.max(0, Math.min(Spiral.MAX_RADIUS, cfg.searchRadius));
        window.allowWater = config.get().safeLanding.allowWater;
        landing.find(at, window).whenComplete((found, error) -> {
            if (error != null) {
                logger.error("Searching a safe login spot for {} at {} failed", player.getName(), describe(at), error);
                return;
            }
            if (found.isEmpty()) {
                logger.warn("{} joined inside blocks at {} and no safe spot was found", player.getName(), describe(at));
                return;
            }
            Location destination = found.get();
            UUID uuid = player.getUniqueId();
            scheduler.entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                startedTeleports.begin(uuid);
                player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((moved, moveError) -> {
                    startedTeleports.end(uuid);
                    if (moveError != null || !Boolean.TRUE.equals(moved)) {
                        logger.warn("Could not move {} out of blocks at {}", player.getName(), describe(at),
                                moveError == null ? null : moveError);
                        return;
                    }
                    messages.send(player, "teleport.safe-login");
                });
            }, null);
        });
    }

    private static boolean insideBlocks(World world, Location at) {
        BoundingBox box = new BoundingBox(at.getX() - 0.3, at.getY() + 0.001, at.getZ() - 0.3,
                at.getX() + 0.3, at.getY() + 1.8, at.getZ() + 0.3);
        return world.hasCollisionsIn(box);
    }

    /** Module disable: stops every warmup. */
    void shutdown() {
        for (Map.Entry<UUID, Warmup> entry : List.copyOf(warmups.entrySet())) {
            abort(entry.getKey(), entry.getValue(), TeleportResult.FAILED, null, null);
        }
        startedTeleports.clearAll();
        failedSpots.clear();
    }
}
