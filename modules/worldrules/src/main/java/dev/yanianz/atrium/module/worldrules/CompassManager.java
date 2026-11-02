package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Compass targets. Tracking another player runs one bounded timer per tracker and stops as soon as the
 * tracker no longer holds a compass or the target leaves; there is no per-tick polling for idle players.
 */
final class CompassManager {
    enum Start {
        STARTED,
        ALREADY,
        BUSY,
        NO_COMPASS
    }

    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    private final Map<UUID, Track> trackers = new ConcurrentHashMap<>();

    CompassManager(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    /** Must run on the holder's own thread. */
    Start start(Player holder, Player target) {
        UUID id = holder.getUniqueId();
        if (!holdsCompass(holder)) {
            return Start.NO_COMPASS;
        }
        Track existing = trackers.get(id);
        if (existing != null && existing.target.equals(target.getUniqueId())) {
            return Start.ALREADY;
        }
        if (existing == null && trackers.size() >= config.get().maxCompassTrackers()) {
            return Start.BUSY;
        }
        if (existing != null) {
            stop(id, null);
        }
        Track track = new Track(target.getUniqueId());
        trackers.put(id, track);
        long period = config.get().compassRefreshTicks();
        track.task = ctx.track(ctx.scheduler().entityTimer(holder, () -> tick(id), () -> stop(id, null), 1, period));
        return Start.STARTED;
    }

    /** Must run on the holder's own thread. */
    void setTarget(Player holder, Location location) {
        stop(holder.getUniqueId(), null);
        holder.setCompassTarget(location);
    }

    private void tick(UUID id) {
        Track track = trackers.get(id);
        if (track == null) {
            return;
        }
        Player holder = Bukkit.getPlayer(id);
        if (holder == null || !holder.isOnline()) {
            stop(id, null);
            return;
        }
        if (!holdsCompass(holder)) {
            stop(id, "worldrules.compass-item-lost");
            return;
        }
        Player target = Bukkit.getPlayer(track.target);
        if (target == null || !target.isOnline()) {
            stop(id, "worldrules.compass-target-left");
            return;
        }
        if (!track.refreshing.compareAndSet(false, true)) {
            return;
        }
        ctx.scheduler().supplyOnEntity(target, target::getLocation).thenAccept(location -> {
            if (location == null) {
                return;
            }
            ctx.scheduler().entity(holder, () -> {
                Track current = trackers.get(id);
                if (current != track) {
                    return;
                }
                if (!holder.isOnline()) {
                    stop(id, null);
                    return;
                }
                if (!holdsCompass(holder)) {
                    stop(id, "worldrules.compass-item-lost");
                    return;
                }
                holder.setCompassTarget(location);
            }, () -> stop(id, null));
        }).exceptionally(error -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            if (!(cause instanceof Scheduler.EntityRetiredException) && !(cause instanceof java.util.concurrent.CancellationException)) {
                ctx.logger().warn("Compass tracking of {} failed", track.target, cause);
            }
            ctx.scheduler().entity(holder, () -> stop(id, "worldrules.compass-target-left"), null);
            return null;
        }).whenComplete((ignored, error) -> track.refreshing.set(false));
    }

    private static boolean holdsCompass(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.getType() == Material.COMPASS) {
            return true;
        }
        ItemStack off = player.getInventory().getItemInOffHand();
        return off.getType() == Material.COMPASS;
    }

    void stopFor(UUID id) {
        stop(id, null);
    }

    void stopAll() {
        for (UUID id : trackers.keySet()) {
            Track track = trackers.remove(id);
            if (track != null && track.task != null) {
                track.task.cancel();
            }
        }
        trackers.clear();
    }

    int tracked() {
        return trackers.size();
    }

    private void stop(UUID id, String messageKey) {
        Track track = trackers.remove(id);
        if (track == null) {
            return;
        }
        if (track.task != null) {
            track.task.cancel();
        }
        if (messageKey == null) {
            return;
        }
        Player holder = Bukkit.getPlayer(id);
        if (holder != null) {
            ctx.scheduler().entity(holder, () -> {
                if (holder.isOnline()) {
                    ctx.messages().send(holder, messageKey);
                }
            }, null);
        }
    }

    private static final class Track {
        final UUID target;
        final AtomicBoolean refreshing = new AtomicBoolean();
        volatile TaskHandle task;

        Track(UUID target) {
            this.target = target;
        }
    }
}
