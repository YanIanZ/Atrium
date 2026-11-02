package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Per-player visibility, run on each player's own thread by an entity timer.
 *
 * <ul>
 *   <li>Shared holograms without a permission are visible by default: nothing to do per player.</li>
 *   <li>Holograms with a permission are hidden by default and shown ({@code showEntity}) to permitted players
 *       in range.</li>
 *   <li>Per-viewer holograms: the player claims a personal copy (up to the per-hologram cap), renders its
 *       text here (PlaceholderAPI on the player's thread) and the hologram's region thread spawns or updates
 *       entities that only this player is shown. Players over the cap are shown the shared copy.</li>
 * </ul>
 *
 * <p>Trade-off of personal copies: correct per-viewer text and pages with plain Paper API (no packets), at the
 * cost of one set of display entities per viewer in range. The cap and the view range bound that cost.
 */
final class ViewerTracker implements Listener {
    /** State of one watched hologram for one player. Touched only on that player's thread. */
    private static final class Watch {
        final HologramRuntime runtime;
        Map<UUID, Entity> shown = new HashMap<>();
        boolean personal;
        long renderedVersion = -1;
        long lastRenderNanos;

        Watch(HologramRuntime runtime) {
            this.runtime = runtime;
        }
    }

    private final ModuleContext ctx;
    private final Displays displays;
    private final Supplier<HologramsConfig> config;
    /** player -> hologram key -> watch. Outer map concurrent (quit/nudge), inner touched on the player's thread. */
    private final Map<UUID, Map<String, Watch>> states = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandle> timers = new ConcurrentHashMap<>();
    private volatile boolean running;

    ViewerTracker(ModuleContext ctx, Displays displays, Supplier<HologramsConfig> config) {
        this.ctx = ctx;
        this.displays = displays;
        this.config = config;
    }

    /**
     * Enables the tracker and starts a timer for every player already online. The flag is set first and does
     * not depend on anyone being online: with an empty server at boot, later joins must still be tracked.
     */
    void startAll(Collection<? extends Player> online) {
        running = true;
        for (Player player : online) {
            start(player);
        }
    }

    boolean running() {
        return running;
    }

    void start(Player player) {
        if (!running) {
            return;
        }
        long period = Math.max(1, config.get().viewerCheckTicks);
        TaskHandle handle = ctx.track(ctx.scheduler().entityTimer(player, () -> check(player), null, 1, period));
        TaskHandle previous = timers.put(player.getUniqueId(), handle);
        if (previous != null) {
            previous.cancel();
        }
    }

    /** Stops every per-player timer; used when the native engine is replaced by an external one. */
    void stop() {
        running = false;
        for (TaskHandle handle : timers.values()) {
            handle.cancel();
        }
        timers.clear();
        states.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (running) {
            start(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        TaskHandle handle = timers.remove(uuid);
        if (handle != null) {
            handle.cancel();
        }
        Map<String, Watch> watches = states.remove(uuid);
        if (watches != null) {
            for (Watch watch : watches.values()) {
                if (watch.personal) {
                    watch.runtime.release(uuid);
                }
            }
        }
    }

    /** Re-checks soon every player watching this hologram (its entities were respawned). */
    void nudgeWatchers(String key) {
        states.forEach((uuid, watches) -> {
            if (watches.containsKey(key)) {
                nudge(uuid);
            }
        });
    }

    void nudge(UUID uuid) {
        if (!running) {
            return;
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            ctx.scheduler().entity(player, () -> check(player), null);
        }
    }

    /** Player thread. */
    void check(Player player) {
        if (!player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Map<String, Watch> watches = states.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>());
        Location at = player.getLocation();
        Set<String> seen = new HashSet<>();
        long now = System.nanoTime();
        int cap = displays.personalCap();
        for (HologramRuntime runtime : displays.inWorld(at.getWorld().getName())) {
            if (runtime.retired()) {
                continue;
            }
            HologramSettings s = runtime.definition().settings();
            StoredLocation l = runtime.location;
            double dx = l.x() - at.getX();
            double dy = l.y() - at.getY();
            double dz = l.z() - at.getZ();
            double range = s.viewRange();
            if (dx * dx + dy * dy + dz * dz > range * range) {
                continue;
            }
            if (!s.permission().isEmpty() && !player.hasPermission(s.permission())) {
                continue;
            }
            seen.add(runtime.key);
            Watch watch = watches.get(runtime.key);
            if (watch != null && watch.runtime != runtime) {
                forget(player, watch); // hologram moved: new runtime, new entities
                watch = null;
            }
            if (watch == null) {
                watch = new Watch(runtime);
                watches.put(runtime.key, watch);
            }
            update(player, watch, s, now, cap);
        }
        Iterator<Map.Entry<String, Watch>> it = watches.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Watch> entry = it.next();
            if (!seen.contains(entry.getKey())) {
                forget(player, entry.getValue());
                it.remove();
            }
        }
    }

    private void update(Player player, Watch watch, HologramSettings s, long now, int cap) {
        HologramRuntime runtime = watch.runtime;
        UUID uuid = player.getUniqueId();
        HologramRuntime.Snapshot snap = runtime.snapshot();
        Map<UUID, Entity> desired = new HashMap<>();
        if (runtime.personalMode()) {
            if (watch.personal || runtime.tryReserve(uuid, cap)) {
                watch.personal = true;
                if (snap.spawned() && needsRender(watch, runtime, s, now)) {
                    watch.renderedVersion = runtime.version();
                    watch.lastRenderNanos = now;
                    runtime.renderPersonal(player);
                }
                addAll(desired, snap.personal().get(uuid));
            } else {
                addAll(desired, snap.shared());
            }
        } else {
            if (watch.personal) {
                runtime.release(uuid);
                watch.personal = false;
                watch.renderedVersion = -1;
            }
            if (snap.sharedHidden()) {
                addAll(desired, snap.shared());
            }
        }
        Entity interaction = snap.interaction();
        if (interaction != null && snap.interactionHidden()
                && (s.clickPermission().isEmpty() || player.hasPermission(s.clickPermission()))) {
            desired.put(interaction.getUniqueId(), interaction);
        }
        sync(player, watch, desired);
    }

    private static boolean needsRender(Watch watch, HologramRuntime runtime, HologramSettings s, long now) {
        if (watch.renderedVersion != runtime.version()) {
            return true;
        }
        int interval = s.updateInterval();
        return runtime.dynamicText() && interval > 0 && now - watch.lastRenderNanos >= interval * 50_000_000L;
    }

    private static void addAll(Map<UUID, Entity> target, List<Entity> entities) {
        if (entities != null) {
            for (Entity entity : entities) {
                target.put(entity.getUniqueId(), entity);
            }
        }
    }

    private void sync(Player player, Watch watch, Map<UUID, Entity> desired) {
        for (Map.Entry<UUID, Entity> entry : desired.entrySet()) {
            if (!watch.shown.containsKey(entry.getKey())) {
                player.showEntity(ctx.plugin(), entry.getValue());
            }
        }
        for (Map.Entry<UUID, Entity> entry : watch.shown.entrySet()) {
            if (!desired.containsKey(entry.getKey())) {
                player.hideEntity(ctx.plugin(), entry.getValue());
            }
        }
        watch.shown = desired;
    }

    private void forget(Player player, Watch watch) {
        for (Entity entity : watch.shown.values()) {
            player.hideEntity(ctx.plugin(), entity);
        }
        watch.shown = new HashMap<>();
        if (watch.personal) {
            watch.runtime.release(player.getUniqueId());
            watch.personal = false;
        }
    }

    /** Player thread: called after a click changed this viewer's personal page. */
    void rerender(Player player, HologramRuntime runtime) {
        Map<String, Watch> watches = states.get(player.getUniqueId());
        Watch watch = watches == null ? null : watches.get(runtime.key);
        if (watch != null && watch.runtime == runtime && watch.personal) {
            watch.renderedVersion = runtime.version();
            watch.lastRenderNanos = System.nanoTime();
        }
        runtime.renderPersonal(player);
    }
}
