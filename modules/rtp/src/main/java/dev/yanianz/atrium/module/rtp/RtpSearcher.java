package dev.yanianz.atrium.module.rtp;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.module.rtp.RtpConfig.WorldSettings;
import java.time.Duration;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The random-location search. Threading:
 * <ol>
 *   <li>the search area (spawn, border) is read once on the global region,</li>
 *   <li>each candidate chunk is loaded with {@link World#getChunkAtAsync(int, int, boolean)} (generating it
 *       only when the world allows it),</li>
 *   <li>the column is evaluated on the region owning it ({@link Scheduler#supplyAt}); a chunk that was
 *       unloaded again in between is simply a failed attempt, never loaded synchronously.</li>
 * </ol>
 * Attempts run one after another per search; the number of searches is bounded globally and per player.
 * A per-world pool of ready spots, refilled in the background, makes most /rtp calls instant.
 */
final class RtpSearcher {
    /** Why a search could not start. */
    enum Refusal { PLAYER_BUSY, SERVER_BUSY }

    private static final Set<Material> WATERY = EnumSet.of(Material.WATER, Material.BUBBLE_COLUMN, Material.KELP,
            Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS);

    private final Scheduler scheduler;
    private final Logger logger;
    private final ConfigHandle<RtpConfig> config;
    private final LocationPool pool = new LocationPool();
    private final AtomicInteger active = new AtomicInteger();
    private final Map<UUID, Integer> perPlayer = new ConcurrentHashMap<>();
    private final Set<String> refilling = ConcurrentHashMap.newKeySet();
    private final Map<List<String>, Set<Material>> avoidCache = new ConcurrentHashMap<>();
    private final Map<List<String>, Set<String>> biomeCache = new ConcurrentHashMap<>();
    private volatile boolean stopped;

    RtpSearcher(ModuleContext ctx, ConfigHandle<RtpConfig> config) {
        this.scheduler = ctx.scheduler();
        this.logger = ctx.logger();
        this.config = config;
    }

    // ---- limits ----

    /**
     * Reserves a search slot for {@code player}; null on success, then {@link #release} must follow exactly once.
     *
     * @param enforceGlobal false for automatic teleports (first join, respawn) that must not be refused
     */
    @Nullable Refusal reserve(UUID player, boolean enforceGlobal) {
        RtpConfig cfg = config.get();
        int maxPerPlayer = Math.max(1, cfg.maxSearchesPerPlayer);
        boolean[] taken = {false};
        perPlayer.compute(player, (k, n) -> {
            int current = n == null ? 0 : n;
            if (current >= maxPerPlayer) {
                return n;
            }
            taken[0] = true;
            return current + 1;
        });
        if (!taken[0]) {
            return Refusal.PLAYER_BUSY;
        }
        int maxGlobal = Math.max(1, cfg.maxConcurrentSearches);
        while (true) {
            int current = active.get();
            if (enforceGlobal && current >= maxGlobal) {
                releasePlayer(player);
                return Refusal.SERVER_BUSY;
            }
            if (active.compareAndSet(current, current + 1)) {
                return null;
            }
        }
    }

    void release(UUID player) {
        active.decrementAndGet();
        releasePlayer(player);
    }

    private void releasePlayer(UUID player) {
        perPlayer.computeIfPresent(player, (k, n) -> n <= 1 ? null : n - 1);
    }

    // ---- search ----

    /** A safe spot: a checked pooled one when available, otherwise a live search. */
    CompletableFuture<Optional<Location>> find(World world, WorldSettings settings) {
        RtpConfig cfg = config.get();
        if (cfg.pool.size > 0) {
            Optional<LocationPool.Spot> spot = pool.poll(world.getName(), System.currentTimeMillis(), maxAgeMillis(cfg));
            if (spot.isPresent()) {
                return revalidate(world, settings, spot.get())
                        .thenCompose(found -> found.isPresent() ? CompletableFuture.completedFuture(found) : searchLive(world, settings));
            }
        }
        return searchLive(world, settings);
    }

    /** Puts an unused spot back (for example when the payment failed). */
    void giveBack(Location location) {
        if (location.getWorld() == null) {
            return;
        }
        pool.offer(location.getWorld().getName(), new LocationPool.Spot(location.getBlockX(), location.getBlockY(),
                location.getBlockZ(), location.getYaw(), System.currentTimeMillis()), Math.max(0, config.get().pool.size));
    }

    private CompletableFuture<Optional<Location>> searchLive(World world, WorldSettings settings) {
        if (stopped) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return scheduler.supplyGlobal(() -> area(world, settings))
                .thenCompose(area -> attempt(world, settings, area, Math.max(1, settings.maxAttempts)));
    }

    /** Read on the global region: centre and world border of the search. */
    private static Sampler.Area area(World world, WorldSettings settings) {
        WorldBorder border = world.getWorldBorder();
        Location borderCenter = border.getCenter();
        double cx;
        double cz;
        switch (settings.centerMode()) {
            case "BORDER" -> {
                cx = borderCenter.getX();
                cz = borderCenter.getZ();
            }
            case "FIXED" -> {
                cx = settings.centerX;
                cz = settings.centerZ;
            }
            default -> {
                Location spawn = world.getSpawnLocation();
                cx = spawn.getX();
                cz = spawn.getZ();
            }
        }
        return new Sampler.Area(cx, cz, settings.minRadius, settings.maxRadius, settings.shapeValue(),
                Sampler.Border.of(borderCenter.getX(), borderCenter.getZ(), border.getSize()));
    }

    private CompletableFuture<Optional<Location>> attempt(World world, WorldSettings settings, Sampler.Area area, int left) {
        if (stopped || left <= 0 || Bukkit.getWorld(world.getUID()) == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Sampler.Point p = Sampler.sample(area, ThreadLocalRandom.current());
        float yaw = ThreadLocalRandom.current().nextInt(360) - 180f;
        return world.getChunkAtAsync(p.x() >> 4, p.z() >> 4, settings.generateChunks)
                .thenCompose(chunk -> chunk == null
                        ? CompletableFuture.completedFuture(Optional.<Location>empty())
                        : scheduler.supplyAt(new Location(world, p.x() + 0.5, 64, p.z() + 0.5),
                                () -> evaluate(world, settings, p.x(), p.z(), yaw)))
                .exceptionally(error -> {
                    logger.warn("RTP attempt at {} {},{} failed", world.getName(), p.x(), p.z(), error);
                    return Optional.empty();
                })
                .thenCompose(found -> found.isPresent()
                        ? CompletableFuture.completedFuture(found)
                        : attempt(world, settings, area, left - 1));
    }

    /** Checks a pooled spot again on its region (blocks or the border may have changed). */
    private CompletableFuture<Optional<Location>> revalidate(World world, WorldSettings settings, LocationPool.Spot spot) {
        return world.getChunkAtAsync(spot.x() >> 4, spot.z() >> 4, false)
                .thenCompose(chunk -> chunk == null
                        ? CompletableFuture.completedFuture(Optional.<Location>empty())
                        : scheduler.supplyAt(new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5),
                                () -> evaluate(world, settings, spot.x(), spot.z(), spot.yaw())))
                .exceptionally(error -> {
                    logger.warn("Checking a pooled RTP spot in {} failed", world.getName(), error);
                    return Optional.empty();
                });
    }

    /** Runs on the region owning (x, z). Never loads a chunk. */
    private Optional<Location> evaluate(World world, WorldSettings settings, int x, int z, float yaw) {
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return Optional.empty();
        }
        WorldBorder border = world.getWorldBorder();
        Location borderCenter = border.getCenter();
        if (!Sampler.Border.of(borderCenter.getX(), borderCenter.getZ(), border.getSize()).containsBlock(x, z)) {
            return Optional.empty();
        }
        int minY = world.getMinHeight();
        boolean roof = world.hasCeiling();
        int ceiling = roof ? Math.min(world.getMaxHeight(), minY + world.getLogicalHeight()) : world.getMaxHeight();
        Set<Material> avoid = avoidSet(settings.avoidBlocks);
        ColumnScan.Column column = y -> classify(world.getBlockAt(x, y, z), settings, avoid, roof);
        OptionalInt y;
        if (roof) {
            y = ColumnScan.belowRoof(column, minY, ceiling);
        } else {
            HeightMap map = settings.avoidLeaves ? HeightMap.MOTION_BLOCKING_NO_LEAVES : HeightMap.MOTION_BLOCKING;
            y = ColumnScan.surface(column, world.getHighestBlockYAt(x, z, map) + 1, minY, ceiling);
        }
        if (y.isEmpty() || !biomeAllowed(world.getBiome(x, y.getAsInt(), z), settings)) {
            return Optional.empty();
        }
        return Optional.of(new Location(world, x + 0.5, y.getAsInt(), z + 0.5, yaw, 0f));
    }

    static ColumnScan.Kind classify(Block block, WorldSettings settings, Set<Material> avoid, boolean roof) {
        Material type = block.getType();
        if (type.isAir()) {
            return ColumnScan.Kind.EMPTY;
        }
        if (type == Material.LAVA || avoid.contains(type) || (roof && type == Material.BEDROCK)) {
            return ColumnScan.Kind.AVOID;
        }
        if (WATERY.contains(type) || (block.getBlockData() instanceof Waterlogged w && w.isWaterlogged())) {
            return settings.avoidWater ? ColumnScan.Kind.AVOID : ColumnScan.Kind.WATER;
        }
        if (Tag.LEAVES.isTagged(type)) {
            return settings.avoidLeaves ? ColumnScan.Kind.AVOID : ColumnScan.Kind.SOLID;
        }
        return block.isPassable() ? ColumnScan.Kind.EMPTY : ColumnScan.Kind.SOLID;
    }

    private Set<Material> avoidSet(List<String> names) {
        List<String> key = names == null ? List.of() : List.copyOf(names);
        return avoidCache.computeIfAbsent(key, list -> {
            Set<Material> set = EnumSet.noneOf(Material.class);
            for (String name : list) {
                Material material = name == null ? null : Material.matchMaterial(name.trim());
                if (material == null) {
                    logger.warn("rtp.yml: unknown block '{}' in avoid-blocks", name);
                } else {
                    set.add(material);
                }
            }
            return set;
        });
    }

    private boolean biomeAllowed(Biome biome, WorldSettings settings) {
        String key = biome.key().asString();
        Set<String> allowed = biomes(settings.allowedBiomes);
        return (allowed.isEmpty() || allowed.contains(key)) && !biomes(settings.blockedBiomes).contains(key);
    }

    private Set<String> biomes(List<String> names) {
        List<String> key = names == null ? List.of() : List.copyOf(names);
        return biomeCache.computeIfAbsent(key, list -> {
            Set<String> set = new HashSet<>();
            for (String name : list) {
                if (name != null && !name.isBlank()) {
                    set.add(normaliseBiome(name));
                }
            }
            return Set.copyOf(set);
        });
    }

    static String normaliseBiome(String name) {
        String lower = name.trim().toLowerCase(Locale.ROOT);
        return lower.indexOf(':') >= 0 ? lower : "minecraft:" + lower;
    }

    // ---- pool ----

    private static long maxAgeMillis(RtpConfig cfg) {
        return Duration.ofMinutes(Math.max(1, cfg.pool.maxAgeMinutes)).toMillis();
    }

    /** Background refill: one search per world at a time, only while players are not using every slot. */
    void refillTick() {
        if (stopped) {
            return;
        }
        RtpConfig cfg = config.get();
        int size = cfg.pool.size;
        if (size <= 0) {
            return;
        }
        pool.expire(System.currentTimeMillis(), maxAgeMillis(cfg));
        if (active.get() >= Math.max(1, cfg.maxConcurrentSearches)) {
            return;
        }
        for (World world : Bukkit.getWorlds()) {
            WorldSettings settings = cfg.settings(world.getName(), world.getEnvironment());
            String name = world.getName();
            if (settings == null || pool.size(name) >= size || !refilling.add(name)) {
                continue;
            }
            searchLive(world, settings).whenComplete((found, error) -> {
                refilling.remove(name);
                if (error != null) {
                    logger.warn("Refilling the RTP pool of {} failed", name, error);
                    return;
                }
                found.ifPresent(location -> pool.offer(name, new LocationPool.Spot(location.getBlockX(),
                        location.getBlockY(), location.getBlockZ(), location.getYaw(), System.currentTimeMillis()),
                        config.get().pool.size));
            });
        }
    }

    int pooled(String world) {
        return pool.size(world);
    }

    /** After a config reload: settings may differ, so forget pooled spots and cached lists. */
    void reset() {
        pool.clear();
        avoidCache.clear();
        biomeCache.clear();
    }

    void stop() {
        stopped = true;
        pool.clear();
    }
}
