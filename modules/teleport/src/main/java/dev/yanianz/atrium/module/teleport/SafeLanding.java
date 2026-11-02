package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.BoundingBox;

/**
 * Finds safe destinations in the real world. The chunk is loaded asynchronously first; the block search
 * then runs on the thread owning that region ({@link Scheduler#supplyAt}). Nothing here loads a chunk
 * synchronously: if the chunk was unloaded again before the search ran, it is reloaded asynchronously and
 * the search retried once.
 *
 * <p>When the destination column has no safe spot, the columns around it are searched nearest first
 * ({@link Spiral}); columns in another chunk are loaded asynchronously and searched on their own region.
 */
final class SafeLanding {
    private static final Set<Material> HAZARDS = EnumSet.of(
            Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
            Material.SWEET_BERRY_BUSH, Material.WITHER_ROSE, Material.POWDER_SNOW, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.COBWEB, Material.POINTED_DRIPSTONE, Material.LAVA_CAULDRON,
            Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY);

    /** Player hitbox half-width and height, for the exact-fit check. */
    private static final double HALF_WIDTH = 0.3;
    private static final double HEIGHT = 1.8;

    private final Scheduler scheduler;

    SafeLanding(Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Resolves a safe spot near {@code target}, or empty when there is none in range. */
    CompletableFuture<Optional<Location>> find(Location target, TeleportConfig.SafeLanding config) {
        return findInColumn(target, config, 1).thenCompose(found -> {
            if (found.isPresent() || config.searchRadius <= 0) {
                return CompletableFuture.completedFuture(found);
            }
            List<List<Spiral.Offset>> runs = Spiral.runsByChunk(target.getBlockX(), target.getBlockZ(), config.searchRadius);
            return searchAround(target, config, runs, 0);
        });
    }

    /** Searches run {@code index} of the spiral, then the following runs until a spot is found. */
    private CompletableFuture<Optional<Location>> searchAround(Location target, TeleportConfig.SafeLanding config,
            List<List<Spiral.Offset>> runs, int index) {
        if (index >= runs.size()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        World world = target.getWorld();
        List<Spiral.Offset> run = runs.get(index);
        Spiral.Offset first = run.getFirst();
        Location anchor = new Location(world, target.getBlockX() + first.dx() + 0.5, target.getY(),
                target.getBlockZ() + first.dz() + 0.5);
        return world.getChunkAtAsync(anchor)
                .thenCompose(chunk -> scheduler.supplyAt(anchor, () -> searchRun(target, run, config)))
                .thenCompose(found -> found.isPresent()
                        ? CompletableFuture.completedFuture(found)
                        : searchAround(target, config, runs, index + 1));
    }

    /** Runs on the region thread owning the run's chunk. A chunk that is not loaded (any more) is skipped. */
    private static Optional<Location> searchRun(Location target, List<Spiral.Offset> run, TeleportConfig.SafeLanding config) {
        World world = target.getWorld();
        int minY = world.getMinHeight();
        int maxY = ceiling(world, config);
        int startY = Math.max(minY + 1, Math.min(maxY - 2, target.getBlockY()));
        for (Spiral.Offset offset : run) {
            int x = target.getBlockX() + offset.dx();
            int z = target.getBlockZ() + offset.dz();
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                return Optional.empty();
            }
            OptionalInt y = SafeSpot.nearest(columnOf(world, x, z), startY, minY, maxY,
                    Math.max(0, config.searchUp), Math.max(0, config.searchDown), config.allowWater);
            if (y.isPresent()) {
                return Optional.of(new Location(world, x + 0.5, y.getAsInt(), z + 0.5, target.getYaw(), target.getPitch()));
            }
        }
        return Optional.empty();
    }

    private CompletableFuture<Optional<Location>> findInColumn(Location target, TeleportConfig.SafeLanding config, int retries) {
        World world = target.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return world.getChunkAtAsync(target)
                .thenCompose(chunk -> scheduler.supplyAt(target, () -> search(target, config)))
                .thenCompose(result -> {
                    if (result != null) {
                        return CompletableFuture.completedFuture(result);
                    }
                    if (retries <= 0) {
                        return CompletableFuture.completedFuture(Optional.<Location>empty());
                    }
                    return findInColumn(target, config, retries - 1);
                });
    }

    /** Highest safe spot in the column of {@code at} (for /top). Must not be used for unloaded chunks. */
    CompletableFuture<Optional<Location>> top(Location at, TeleportConfig.SafeLanding config) {
        World world = at.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return scheduler.supplyAt(at, () -> {
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                return Optional.<Location>empty();
            }
            int minY = world.getMinHeight();
            int maxY = ceiling(world, config);
            SafeSpot.Column column = columnOf(world, at.getBlockX(), at.getBlockZ());
            OptionalInt y = SafeSpot.highest(column, minY, maxY, config.allowWater);
            if (y.isEmpty()) {
                return Optional.<Location>empty();
            }
            return Optional.of(new Location(world, at.getBlockX() + 0.5, y.getAsInt(), at.getBlockZ() + 0.5,
                    at.getYaw(), at.getPitch()));
        });
    }

    /** Lowest safe spot at or below {@code at} (for /down). Must not be used for unloaded chunks. */
    CompletableFuture<Optional<Location>> down(Location at, TeleportConfig.SafeLanding config) {
        World world = at.getWorld();
        if (world == null) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        return scheduler.supplyAt(at, () -> {
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                return Optional.<Location>empty();
            }
            int minY = world.getMinHeight();
            int maxY = ceiling(world, config);
            SafeSpot.Column column = columnOf(world, at.getBlockX(), at.getBlockZ());
            OptionalInt y = SafeSpot.lowest(column, at.getBlockY(), minY, maxY, config.allowWater);
            if (y.isEmpty()) {
                return Optional.<Location>empty();
            }
            return Optional.of(new Location(world, at.getX(), y.getAsInt(), at.getZ(), at.getYaw(), at.getPitch()));
        });
    }

    /** Runs on the region thread. Returns null when the chunk is not loaded (caller retries). */
    private static Optional<Location> search(Location target, TeleportConfig.SafeLanding config) {        World world = target.getWorld();
        int bx = target.getBlockX();
        int bz = target.getBlockZ();
        if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
            return null;
        }
        int minY = world.getMinHeight();
        int maxY = ceiling(world, config);
        int blockY = target.getBlockY();
        int startY = Math.max(minY + 1, Math.min(maxY - 2, blockY));
        SafeSpot.Column column = columnOf(world, bx, bz);
        OptionalInt found = SafeSpot.nearest(column, startY, minY, maxY,
                Math.max(0, config.searchUp), Math.max(0, config.searchDown), config.allowWater);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        int y = found.getAsInt();
        // The block-level check also rejects standing on top of a slab or carpet inside the feet block;
        // keep the exact location when the player's hitbox fits there.
        if ((y == blockY || y == blockY + 1) && blockY == startY && fits(world, target)) {
            return Optional.of(target.clone());
        }
        return Optional.of(new Location(world, target.getX(), y, target.getZ(), target.getYaw(), target.getPitch()));
    }

    private static int ceiling(World world, TeleportConfig.SafeLanding config) {
        int max = world.getMaxHeight();
        if (config.allowNetherRoof) {
            return max;
        }
        return Math.min(max, world.getMinHeight() + world.getLogicalHeight());
    }

    private static boolean fits(World world, Location at) {
        BoundingBox box = new BoundingBox(at.getX() - HALF_WIDTH, at.getY() + 0.001, at.getZ() - HALF_WIDTH,
                at.getX() + HALF_WIDTH, at.getY() + HEIGHT, at.getZ() + HALF_WIDTH);
        return !world.hasCollisionsIn(box);
    }

    private static SafeSpot.Column columnOf(World world, int x, int z) {
        return y -> classify(world.getBlockAt(x, y, z));
    }

    static SafeSpot.Kind classify(Block block) {
        Material type = block.getType();
        if (type.isAir()) {
            return SafeSpot.Kind.EMPTY;
        }
        if (HAZARDS.contains(type)) {
            return SafeSpot.Kind.HAZARD;
        }
        if (type == Material.WATER || type == Material.BUBBLE_COLUMN) {
            return SafeSpot.Kind.WATER;
        }
        return block.isPassable() ? SafeSpot.Kind.EMPTY : SafeSpot.Kind.SOLID;
    }
}
