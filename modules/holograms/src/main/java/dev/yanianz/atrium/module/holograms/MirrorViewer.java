package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Range, permission and per-viewer page handling for external engines. The external plugin only knows which
 * player sees which hologram and, for DecentHolograms, which page; Atrium still decides the audience.
 *
 * <p>Runs on each player's own thread, every {@code viewerCheckTicks}, and only calls the engine when what
 * the player should see changed. Nothing runs while a player is idle.
 */
final class MirrorViewer implements Listener {
    interface Host {
        Collection<Hologram> holograms();

        void show(Player viewer, Hologram hologram, int page);

        void hide(Player viewer, Hologram hologram);
    }

    /** One hologram as last sent to one viewer. Touched only on that viewer's thread. */
    private static final class Shown {
        boolean visible;
        int page;
        long lastPageNanos;

        Shown(long now) {
            this.lastPageNanos = now;
        }
    }

    private final ModuleContext ctx;
    private final Supplier<HologramsConfig> config;
    private final Host host;
    private final boolean pageCycling;
    private final Map<UUID, Map<String, Shown>> states = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandle> timers = new ConcurrentHashMap<>();
    private volatile boolean running;

    MirrorViewer(ModuleContext ctx, Supplier<HologramsConfig> config, Host host, boolean pageCycling) {
        this.ctx = ctx;
        this.config = config;
        this.host = host;
        this.pageCycling = pageCycling;
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

    void startAll() {
        running = true;
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            start(player);
        }
    }

    void stop() {
        running = false;
        for (TaskHandle handle : timers.values()) {
            handle.cancel();
        }
        timers.clear();
        states.clear();
    }

    /** A mirror was deleted and recreated; every viewer has to be shown the new one again. */
    void forget(String key) {
        for (Map<String, Shown> shown : states.values()) {
            shown.remove(key);
        }
    }

    /**
     * The external plugin dropped and recreated its holograms: nobody sees a mirror any more, so every viewer
     * is shown each one again on the next check (no stale {@code visible = true}).
     */
    void reset() {
        for (Map<String, Shown> shown : states.values()) {
            shown.clear();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        TaskHandle handle = timers.remove(uuid);
        if (handle != null) {
            handle.cancel();
        }
        states.remove(uuid);
    }

    /** Player thread. */
    void check(Player player) {
        if (!running || !player.isOnline()) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Map<String, Shown> seenByPlayer = states.computeIfAbsent(uuid, key -> new ConcurrentHashMap<>());
        Location at = player.getLocation();
        Set<String> seen = new HashSet<>();
        long now = System.nanoTime();
        for (Hologram hologram : host.holograms()) {
            HologramSettings settings = hologram.settings();
            if (!settings.enabled()) {
                continue;
            }
            if (config.get().perHologramPermission
                    && !player.hasPermission(ViewerTracker.PER_HOLOGRAM_PERMISSION + hologram.key())) {
                continue;
            }
            StoredLocation location = hologram.location();
            if (!location.world().equals(at.getWorld().getName())) {
                continue;
            }
            double dx = location.x() - at.getX();
            double dy = location.y() - at.getY();
            double dz = location.z() - at.getZ();
            double range = settings.viewRange();
            if (dx * dx + dy * dy + dz * dz > range * range) {
                continue;
            }
            if (!settings.permission().isEmpty() && !player.hasPermission(settings.permission())) {
                continue;
            }
            seen.add(hologram.key());
            Shown shown = seenByPlayer.computeIfAbsent(hologram.key(), key -> new Shown(now));
            int page = page(hologram, shown, now);
            if (!shown.visible || shown.page != page) {
                shown.visible = true;
                shown.page = page;
                host.show(player, hologram, page);
            }
        }
        Iterator<Map.Entry<String, Shown>> iterator = seenByPlayer.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Shown> entry = iterator.next();
            if (seen.contains(entry.getKey())) {
                continue;
            }
            Hologram hologram = find(entry.getKey());
            if (hologram != null) {
                host.hide(player, hologram);
            }
            iterator.remove();
        }
    }

    private Hologram find(String key) {
        for (Hologram hologram : host.holograms()) {
            if (hologram.key().equals(key)) {
                return hologram;
            }
        }
        return null;
    }

    /** Per-viewer page for engines with pages; the page timer runs per viewer so it is not multiplied by N. */
    private int page(Hologram hologram, Shown shown, long now) {
        int count = hologram.pages().size();
        int interval = hologram.settings().pageInterval();
        if (!pageCycling || count <= 1 || interval <= 0) {
            shown.page = 0;
            return 0;
        }
        long intervalNanos = interval * 50_000_000L;
        long elapsed = now - shown.lastPageNanos;
        if (elapsed >= intervalNanos) {
            long steps = elapsed / intervalNanos;
            shown.page = (int) ((shown.page + steps) % count);
            shown.lastPageNanos += steps * intervalNanos;
        }
        return Pages.clamp(shown.page, count);
    }
}
