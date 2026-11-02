package dev.yanianz.atrium.module.npcs;

import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;

/**
 * Per-NPC look-at timer for native mannequins. A timer exists only while at least one player is within the
 * NPC's range; it cancels itself when the last player leaves, and {@link #touch(Player)} starts it again when
 * a player walks or teleports close. There is no global per-tick loop.
 */
final class NativeLookAt {
    private final ModuleContext ctx;
    private final Supplier<NpcsConfig> config;
    private final Supplier<Iterable<NpcDef>> definitions;
    private final Function<String, Entity> entityLookup;
    private final Map<String, TaskHandle> timers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTouch = new ConcurrentHashMap<>();

    NativeLookAt(ModuleContext ctx, Supplier<NpcsConfig> config, Supplier<Iterable<NpcDef>> definitions,
            Function<String, Entity> entityLookup) {
        this.ctx = ctx;
        this.config = config;
        this.definitions = definitions;
        this.entityLookup = entityLookup;
    }

    /** Starts the timer for one NPC when it is enabled and not already running. Region thread. */
    void ensure(NpcDef def) {
        if (!def.lookAt() || timers.containsKey(def.key())) {
            return;
        }
        Entity entity = entityLookup.apply(def.key());
        if (!(entity instanceof Mannequin) || !entity.isValid()) {
            return;
        }
        long period = Math.max(5, Math.min(100, config.get().lookIntervalTicks));
        Location location = def.location().toLocation().orElse(null);
        if (location == null) {
            return;
        }
        TaskHandle handle = ctx.scheduler().regionTimer(location, () -> tick(def), 1, period);
        if (handle != TaskHandle.NOOP) {
            timers.put(def.key(), handle);
        }
    }

    private void tick(NpcDef def) {
        Entity entity = entityLookup.apply(def.key());
        if (!(entity instanceof Mannequin mannequin) || !mannequin.isValid()) {
            stop(def.key());
            return;
        }
        Location center = mannequin.getLocation();
        var players = mannequin.getWorld().getNearbyPlayers(center, Math.max(0.5, def.lookRange()));
        if (players.isEmpty()) {
            stop(def.key());
            return;
        }
        Player nearest = null;
        double best = Double.MAX_VALUE;
        for (Player player : players) {
            double distance = player.getLocation().distanceSquared(center);
            if (distance < best) {
                best = distance;
                nearest = player;
            }
        }
        if (nearest == null) {
            stop(def.key());
            return;
        }
        float[] rotation = NpcGeometry.lookAt(mannequin.getEyeLocation(), nearest.getEyeLocation());
        mannequin.setRotation(rotation[0], rotation[1]);
    }

    /** Called from a player's thread on move/teleport/join: starts timers for NPCs that came into range. */
    void touch(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastTouch.get(player.getUniqueId());
        if (previous != null && now - previous < 750) {
            return;
        }
        lastTouch.put(player.getUniqueId(), now);
        Location location = player.getLocation();
        for (NpcDef def : definitions.get()) {
            if (!def.lookAt() || timers.containsKey(def.key()) || !def.world().equals(location.getWorld().getName())) {
                continue;
            }
            double range = Math.max(0.5, def.lookRange());
            if (location.distanceSquared(new Location(location.getWorld(), def.x(), def.y(), def.z())) > range * range) {
                continue;
            }
            ctx.scheduler().region(def.location().toLocation().orElse(location), () -> ensure(def));
        }
    }

    void stop(String key) {
        TaskHandle handle = timers.remove(key);
        if (handle != null) {
            handle.cancel();
        }
    }

    void removePlayer(UUID uuid) {
        lastTouch.remove(uuid);
    }

    void shutdown() {
        timers.values().forEach(TaskHandle::cancel);
        timers.clear();
        lastTouch.clear();
    }
}
