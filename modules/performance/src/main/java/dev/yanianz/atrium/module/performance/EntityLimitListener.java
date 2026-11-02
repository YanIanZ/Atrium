package dev.yanianz.atrium.module.performance;

import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;

/**
 * Per-chunk entity caps checked on the region thread of the spawn event.
 *
 * <p>The cheapest possible path runs first: {@link Chunk#getEntities()} is already the array needed
 * for the full count, and fewer than half the cap of <em>all</em> entities proves the category is
 * under its cap. Only then are entities classified. A candidate already present in the chunk (some
 * events fire after insertion) is excluded so the cap is the resulting maximum, not one less.
 *
 * <p>Items a player or a block produced are never cancelled. Death loot, Q-drops and the contents of a
 * broken container are announced by their own events, which {@link DropGuard} remembers for a moment;
 * when the cap is reached, the oldest natural item is evicted instead so the fresh drop survives.
 */
final class EntityLimitListener implements Listener {
    /** Items younger than this cannot be the oldest candidate, and are still part of the same burst. */
    private static final int MIN_EVICTABLE_TICKS = 200;

    private final ModuleContext ctx;
    private final Supplier<PerformanceConfig> config;
    private final EntityLimitStats stats = new EntityLimitStats();
    private final DropGuard drops = new DropGuard();
    private final AtomicLong lastLog = new AtomicLong();

    EntityLimitListener(ModuleContext ctx, Supplier<PerformanceConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    EntityLimitStats.Snapshot stats() {
        return stats.snapshot();
    }

    @SuppressWarnings("removal")
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!config.get().entityLimits.enabled
                || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CHUNK_GEN) {
            return;
        }
        deny(event.getEntity(), () -> event.setCancelled(true));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (!config.get().entityLimits.enabled) {
            return;
        }
        Item item = event.getEntity();
        // The event also fires for items restored from disk. Those carry their saved age and must
        // never be discarded just because a chunk was loaded.
        if (item.getTicksLived() > 1) {
            return;
        }
        if (!isProtectedDrop(item)) {
            deny(item, () -> event.setCancelled(true));
            return;
        }
        Limit limit = measure(item);
        if (limit == null) {
            return;
        }
        Item replaced = oldestNatural(limit.chunk(), item);
        if (replaced == null) {
            // Nothing safe to remove: exceeding the cap is better than destroying a player's drop.
            ctx.logger().debug("Entity limit: kept a protected item spawn in chunk {},{} of {} above the cap",
                    limit.x(), limit.z(), limit.world().getName());
            return;
        }
        replaced.remove();
        stats.record(limit.category());
        logLimit(limit, "replaced the oldest natural item to keep a protected drop");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (EntityCategory.of(event.getVehicle()) == EntityCategory.MINECARTS) {
            deny(event.getVehicle(), () -> event.setCancelled(true));
        }
    }

    // ---- protected drops ------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent event) {
        if (!config.get().entityLimits.enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        drops.protect(DropGuard.chunkKey(event.getPlayer().getLocation()), now);
        Item drop = event.getItemDrop();
        if (drop != null) {
            drops.protect(DropGuard.chunkKey(drop.getLocation()), now);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!config.get().entityLimits.enabled) {
            return;
        }
        drops.protect(DropGuard.chunkKey(event.getEntity().getLocation()), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDrop(BlockDropItemEvent event) {
        if (!config.get().entityLimits.enabled) {
            return;
        }
        long now = System.currentTimeMillis();
        drops.protect(DropGuard.chunkKey(event.getBlock().getLocation()), now);
        for (Item item : event.getItems()) {
            drops.protect(DropGuard.chunkKey(item.getLocation()), now);
        }
    }

    /**
     * A broken container drops its contents without one {@link BlockDropItemEvent} per stack, so the
     * chunk is guarded as soon as a container is broken. Ordinary block breaks are not guarded, or a
     * mining area would permanently disable the item cap.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!config.get().entityLimits.enabled) {
            return;
        }
        if (event.getBlock().getState(false) instanceof Container) {
            drops.protect(DropGuard.chunkKey(event.getBlock().getLocation()), System.currentTimeMillis());
        }
    }

    // ---- limits ---------------------------------------------------------------------------------

    private void deny(Entity entity, Runnable cancel) {
        Limit limit = measure(entity);
        if (limit == null) {
            return;
        }
        cancel.run();
        stats.record(limit.category());
        logLimit(limit, "blocked a " + limit.category().name().toLowerCase(Locale.ROOT) + " spawn");
    }

    /** @return the reached cap, or {@code null} when the entity may spawn. */
    private Limit measure(Entity entity) {
        PerformanceConfig cfg = config.get();
        if (!cfg.entityLimits.enabled) {
            return null;
        }
        EntityCategory category = EntityCategory.of(entity);
        if (category == null) {
            return null;
        }
        int cap = cfg.entityLimits.cap(category);
        if (cap <= 0) {
            return null;
        }
        Location location = entity.getLocation();
        World world = location.getWorld();
        if (world == null) {
            return null;
        }
        int x = location.getBlockX() >> 4;
        int z = location.getBlockZ() >> 4;
        // Events are delivered on the owning region thread. Keep the explicit guard anyway: a
        // third-party plugin calling the event manually must never make us read another region.
        if (!world.isChunkLoaded(x, z) || !Bukkit.isOwnedByCurrentRegion(world, x, z)) {
            return null;
        }
        Chunk chunk = world.getChunkAt(x, z);
        if (!chunk.isEntitiesLoaded()) {
            return null;
        }
        Entity[] all = chunk.getEntities();
        if (EntityLimits.underHalf(all.length, cap)) {
            return null;
        }
        int current = 0;
        for (Entity other : all) {
            if (other.getUniqueId().equals(entity.getUniqueId())) {
                continue;
            }
            if (category.matches(other)) {
                current++;
            }
        }
        if (EntityLimits.allowed(current, cap)) {
            return null;
        }
        return new Limit(category, chunk, world, x, z, current, cap);
    }

    private boolean isProtectedDrop(Item item) {
        // A player-thrown item carries the thrower UUID. Death loot and container contents have no
        // thrower, so the events that announce them guard their chunk through DropGuard.
        return item.getThrower() != null
                || drops.isProtected(DropGuard.chunkKey(item.getLocation()), System.currentTimeMillis());
    }

    /**
     * Oldest item that is safe to remove: it must not have been thrown by a player and must be older
     * than the burst that is spawning right now.
     */
    private Item oldestNatural(Chunk chunk, Item fresh) {
        Item oldest = null;
        for (Entity other : chunk.getEntities()) {
            if (!(other instanceof Item item) || item.getUniqueId().equals(fresh.getUniqueId())) {
                continue;
            }
            if (item.getThrower() != null || item.getTicksLived() < MIN_EVICTABLE_TICKS) {
                continue;
            }
            if (oldest == null || item.getTicksLived() > oldest.getTicksLived()) {
                oldest = item;
            }
        }
        return oldest;
    }

    private void logLimit(Limit limit, String detail) {
        PerformanceConfig cfg = config.get();
        long now = System.currentTimeMillis();
        long interval = cfg.entityLimits.logIntervalMillis();
        long previous = lastLog.get();
        if (now - previous < interval || !lastLog.compareAndSet(previous, now)) {
            return;
        }
        ctx.logger().warn("Entity limit: {} in chunk {},{} of {} ({} in chunk, cap {}); "
                        + "further messages are rate-limited", detail, limit.x(), limit.z(),
                limit.world().getName(), limit.current(), limit.cap());
    }

    private record Limit(EntityCategory category, Chunk chunk, World world, int x, int z, int current, int cap) {}
}
