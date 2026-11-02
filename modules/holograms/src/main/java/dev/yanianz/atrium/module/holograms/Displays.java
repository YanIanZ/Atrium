package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * All hologram runtimes, indexed by name, world and chunk, plus the chunk lifecycle: holograms spawn when
 * their chunk loads and are removed when it unloads. Every entity is non-persistent and tagged with the
 * hologram name in its PersistentDataContainer; tagged entities found in a loading chunk are leftovers
 * (e.g. from a crash) and are removed.
 */
final class Displays implements Listener {
    record ChunkKey(String world, int x, int z) {
        static ChunkKey of(StoredLocation l) {
            return new ChunkKey(l.world(), l.blockX() >> 4, l.blockZ() >> 4);
        }

        static ChunkKey of(Chunk chunk) {
            return new ChunkKey(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        }
    }

    private final ModuleContext ctx;
    private final Supplier<HologramsConfig> config;
    private final TextRenderer renderer;
    private final NamespacedKey tagKey;
    private final Map<String, HologramRuntime> runtimes = new ConcurrentHashMap<>();
    private final Map<ChunkKey, Set<String>> byChunk = new ConcurrentHashMap<>();
    private final Map<String, Map<String, HologramRuntime>> byWorld = new ConcurrentHashMap<>();
    private final Map<UUID, String> interactions = new ConcurrentHashMap<>();
    private volatile Layout.Metrics metrics;
    private volatile @Nullable ViewerTracker tracker;

    Displays(ModuleContext ctx, Supplier<HologramsConfig> config, TextRenderer renderer, NamespacedKey tagKey) {
        this.ctx = ctx;
        this.config = config;
        this.renderer = renderer;
        this.tagKey = tagKey;
        this.metrics = config.get().metrics();
    }

    void setTracker(ViewerTracker tracker) {
        this.tracker = tracker;
    }

    Scheduler scheduler() {
        return ctx.scheduler();
    }

    Logger logger() {
        return ctx.logger();
    }

    TextRenderer renderer() {
        return renderer;
    }

    Layout.Metrics metrics() {
        return metrics;
    }

    int tickPeriod() {
        return Math.max(1, Math.min(200, config.get().hologramTickPeriod));
    }

    int personalCap() {
        return Math.max(0, config.get().maxPersonalViewers);
    }

    TaskHandle track(TaskHandle handle) {
        return ctx.track(handle);
    }

    // ---- registry ----

    void loadAll(Collection<Hologram> holograms) {
        for (Hologram hologram : holograms) {
            add(new HologramRuntime(this, hologram)).scheduleSpawn();
        }
    }

    private HologramRuntime add(HologramRuntime runtime) {
        HologramRuntime previous = runtimes.put(runtime.key, runtime);
        if (previous != null) {
            unindex(previous);
            previous.retire();
        }
        byChunk.computeIfAbsent(ChunkKey.of(runtime.location), k -> ConcurrentHashMap.newKeySet()).add(runtime.key);
        byWorld.computeIfAbsent(runtime.location.world(), k -> new ConcurrentHashMap<>()).put(runtime.key, runtime);
        return runtime;
    }

    private void unindex(HologramRuntime runtime) {
        Set<String> inChunk = byChunk.get(ChunkKey.of(runtime.location));
        if (inChunk != null) {
            inChunk.remove(runtime.key);
        }
        Map<String, HologramRuntime> inWorld = byWorld.get(runtime.location.world());
        if (inWorld != null) {
            inWorld.remove(runtime.key, runtime);
        }
    }

    /** Applies a stored change to the world. Called from {@link HologramManager} (any thread). */
    void changed(@Nullable Hologram old, @Nullable Hologram next) {
        if (next == null) {
            if (old != null) {
                HologramRuntime runtime = runtimes.remove(old.key());
                if (runtime != null) {
                    unindex(runtime);
                    runtime.retire();
                }
            }
            return;
        }
        HologramRuntime current = runtimes.get(next.key());
        if (current == null || old == null || next.movedFrom(old)) {
            // A new location is a new runtime: the old one removes its entities on its own region.
            add(new HologramRuntime(this, next)).scheduleSpawn();
        } else {
            current.update(next);
        }
    }

    /** After a config reload: new bounds and defaults for every hologram. */
    void refreshAll() {
        metrics = config.get().metrics();
        for (HologramRuntime runtime : runtimes.values()) {
            runtime.update(runtime.definition());
        }
    }

    @Nullable HologramRuntime runtime(String name) {
        return runtimes.get(Hologram.key(name));
    }

    Collection<HologramRuntime> inWorld(String world) {
        Map<String, HologramRuntime> map = byWorld.get(world);
        return map == null ? List.of() : map.values();
    }

    void shutdown() {
        boolean canSchedule = ctx.plugin().isEnabled();
        for (HologramRuntime runtime : runtimes.values()) {
            if (canSchedule) {
                runtime.retire();
            }
        }
        if (!canSchedule && !runtimes.isEmpty()) {
            // Server stop: entities are non-persistent, so they are not saved with the chunks.
            logger().debug("Holograms not despawned explicitly while the plugin is disabling");
        }
        runtimes.clear();
        byChunk.clear();
        byWorld.clear();
        interactions.clear();
    }

    // ---- entity tags ----

    void tag(Entity entity, String key) {
        entity.getPersistentDataContainer().set(tagKey, PersistentDataType.STRING, key);
    }

    @Nullable String taggedKey(Entity entity) {
        return entity.getPersistentDataContainer().get(tagKey, PersistentDataType.STRING);
    }

    void registerInteraction(UUID entity, String key) {
        interactions.put(entity, key);
    }

    void unregisterInteraction(UUID entity) {
        interactions.remove(entity);
    }

    /** The hologram whose click box this entity is. */
    @Nullable HologramRuntime interactionOwner(UUID entity) {
        String key = interactions.get(entity);
        return key == null ? null : runtimes.get(key);
    }

    /**
     * Region thread of the chunk: removes tagged entities of {@code key} (not spawned yet, so any are
     * leftovers) and of holograms that no longer exist.
     */
    void sweepOrphans(Chunk chunk, String key) {
        int removed = 0;
        for (Entity entity : chunk.getEntities()) {
            String tagged = taggedKey(entity);
            if (tagged != null && (tagged.equals(key) || !runtimes.containsKey(tagged))) {
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) {
            logger().info("Removed {} leftover hologram entities in chunk {},{} of {}", removed, chunk.getX(), chunk.getZ(),
                    chunk.getWorld().getName());
        }
    }

    // ---- viewers ----

    void nudgeViewers(HologramRuntime runtime) {
        ViewerTracker t = tracker;
        if (t != null) {
            t.nudgeWatchers(runtime.key);
        }
    }

    void nudge(UUID viewer) {
        ViewerTracker t = tracker;
        if (t != null) {
            t.nudge(viewer);
        }
    }

    // ---- chunk lifecycle (events fire on the region thread owning the chunk) ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        int removed = 0;
        for (Entity entity : event.getEntities()) {
            if (taggedKey(entity) != null) {
                // Hologram entities are never saved; one coming from disk is a leftover of a crash or old version.
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) {
            logger().info("Removed {} stale hologram entities loaded with chunk {},{} of {}", removed,
                    event.getChunk().getX(), event.getChunk().getZ(), event.getWorld().getName());
        }
        spawnIn(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        spawnIn(event.getChunk());
    }

    private void spawnIn(Chunk chunk) {
        Set<String> keys = byChunk.get(ChunkKey.of(chunk));
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            HologramRuntime runtime = runtimes.get(key);
            if (runtime != null) {
                runtime.scheduleSpawnSoon();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        despawnIn(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        despawnIn(event.getChunk());
    }

    private void despawnIn(Chunk chunk) {
        Set<String> keys = byChunk.get(ChunkKey.of(chunk));
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (String key : keys) {
            HologramRuntime runtime = runtimes.get(key);
            if (runtime != null) {
                runtime.despawn();
            }
        }
    }
}
