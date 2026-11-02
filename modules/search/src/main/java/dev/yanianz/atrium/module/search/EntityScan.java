package dev.yanianz.atrium.module.search;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * {@code /scan entities}: lag finder listing the busiest loaded chunks.
 *
 * <p>{@code World#getLoadedChunks()} is not safe to call from a region thread on Folia (it walks
 * every region's chunk map), so the scan instead walks each online player's surroundings within the
 * view distance on that player's own region thread. Only chunks owned by the current region are
 * touched; no chunk is loaded, generated or unloaded.
 */
final class EntityScan extends ScanJob {
    record Counts(int x, int z, int entities, int tiles) {}

    private final ModuleContext ctx;
    private final Scans scans;
    private final Supplier<SearchConfig> config;
    private final World world;

    EntityScan(ModuleContext ctx, Scans scans, Supplier<SearchConfig> config, UUID owner, String ownerName,
            World world) {
        super(owner, ownerName);
        this.ctx = ctx;
        this.scans = scans;
        this.config = config;
        this.world = world;
    }

    @Override
    void begin() {
        SearchConfig cfg = config.get();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (world != null) {
            players.removeIf(player -> !player.getWorld().equals(world));
        }
        boolean cappedPlayers = players.size() > cfg.maxEntityScanPlayers();
        if (cappedPlayers) {
            players = new ArrayList<>(players.subList(0, cfg.maxEntityScanPlayers()));
        }
        if (players.isEmpty()) {
            finished = true;
            scans.finished(this);
            Scans.deliver(ctx, owner(), audience -> ctx.messages().send(audience, "search.entities-no-players"));
            return;
        }

        int playerCount = players.size();
        Scans.deliver(ctx, owner(), audience -> ctx.messages().send(audience, "search.entities-started",
                Msg.number("players", playerCount)));

        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger chunks = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean capped = new java.util.concurrent.atomic.AtomicBoolean();
        Map<String, Counts> combined = new ConcurrentHashMap<>();
        int cap = cfg.maxEntityScanChunks();
        List<CompletableFuture<?>> futures = new ArrayList<>(playerCount);
        for (Player player : players) {
            String name = player.getName();
            UUID id = player.getUniqueId();
            try {
                futures.add(ctx.scheduler()
                        .supplyOnEntity(player, () -> scanAround(player, seen, chunks, cap, capped))
                        .thenAccept(combined::putAll)
                        .exceptionally(error -> {
                            ctx.logger().warn("The entity scan around {} ({}) failed", name, id, error);
                            return null;
                        }));
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot schedule the entity scan around {} ({})", name, id, e);
            }
        }

        CompletableFuture<?> all = CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
        all.completeOnTimeout(null, cfg.entityScanTimeoutSeconds(), TimeUnit.SECONDS)
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        ctx.logger().warn("The entity scan of {} failed", owner(), error);
                    }
                    report(combined, chunks.get(), capped.get(), cap, playerCount, cappedPlayers);
                });
    }

    /** Runs on one player's region thread; returns only chunks owned by that region. */
    private Map<String, Counts> scanAround(Player player, Set<String> seen, AtomicInteger chunks, int cap,
            java.util.concurrent.atomic.AtomicBoolean capped) {
        World regionWorld = player.getWorld();
        Location location = player.getLocation();
        int centerX = location.getBlockX() >> 4;
        int centerZ = location.getBlockZ() >> 4;
        int view = Math.max(1, Math.min(regionWorld.getViewDistance(), 32));
        Map<String, Counts> local = new HashMap<>();
        for (int dx = -view; dx <= view; dx++) {
            for (int dz = -view; dz <= view; dz++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                if (!regionWorld.isChunkLoaded(x, z) || !Bukkit.isOwnedByCurrentRegion(regionWorld, x, z)) {
                    continue;
                }
                String key = regionWorld.getName() + ":" + x + ":" + z;
                if (!seen.add(key)) {
                    continue;
                }
                if (chunks.get() >= cap) {
                    capped.set(true);
                    continue;
                }
                chunks.incrementAndGet();
                Chunk chunk = regionWorld.getChunkAt(x, z);
                int entities = chunk.isEntitiesLoaded() ? chunk.getEntities().length : 0;
                int tiles = chunk.getTileEntities().length;
                local.put(key, new Counts(x, z, entities, tiles));
            }
        }
        return local;
    }

    private void report(Map<String, Counts> combined, int chunkCount, boolean capped, int cap, int playerCount,
            boolean cappedPlayers) {
        boolean wasCancelled = cancelled;
        finished = true;
        scans.finished(this);
        if (stopped) {
            return;
        }
        SearchConfig cfg = config.get();

        Map<String, Integer> byEntities = new HashMap<>();
        Map<String, Integer> byTiles = new HashMap<>();
        for (Map.Entry<String, Counts> entry : combined.entrySet()) {
            Counts counts = entry.getValue();
            if (counts.entities() > 0) {
                byEntities.put(entry.getKey(), counts.entities());
            }
            if (counts.tiles() > 0) {
                byTiles.put(entry.getKey(), counts.tiles());
            }
        }
        List<TopN.Row> topEntities = TopN.top(byEntities, cfg.topChunkCount());
        List<TopN.Row> topTiles = TopN.top(byTiles, cfg.topChunkCount());
        long secs = seconds();
        String worldName = world == null ? "*" : world.getName();
        int players = playerCount;

        Scans.deliver(ctx, owner(), audience -> {
            if (wasCancelled) {
                ctx.messages().send(audience, "search.entities-cancelled");
                return;
            }
            ctx.messages().send(audience, "search.entities-header",
                    Msg.unparsed("world", worldName),
                    Msg.number("players", players),
                    Msg.number("chunks", chunkCount));
            if (cappedPlayers) {
                ctx.messages().send(audience, "search.entities-players-capped", Msg.number("max", players));
            }
            if (topEntities.isEmpty() && topTiles.isEmpty()) {
                ctx.messages().send(audience, "search.entities-empty");
            } else {
                sendRows(audience, topEntities, "search.entities-entity-header", "search.entities-entity-line");
                sendRows(audience, topTiles, "search.entities-tile-header", "search.entities-tile-line");
            }
            if (capped) {
                ctx.messages().send(audience, "search.entities-capped", Msg.number("max", cap));
            }
            ctx.messages().send(audience, "search.entities-done", Msg.number("seconds", secs));
        });
    }

    private void sendRows(Audience audience, List<TopN.Row> rows, String headerKey, String lineKey) {
        if (rows.isEmpty()) {
            return;
        }
        ctx.messages().send(audience, headerKey);
        int rank = 1;
        for (TopN.Row row : rows) {
            String[] parts = row.key().split(":", 3);
            ctx.messages().send(audience, lineKey,
                    Msg.number("rank", rank++),
                    Msg.unparsed("world", parts.length > 0 ? parts[0] : "?"),
                    Msg.number("x", parseInt(parts, 1)),
                    Msg.number("z", parseInt(parts, 2)),
                    Msg.number("count", row.count()));
        }
    }

    private static int parseInt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void cancel() {
        super.cancel();
        // Let the owner start another scan immediately; the pending report still says "cancelled".
        scans.finished(this);
    }
}
