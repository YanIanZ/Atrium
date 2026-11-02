package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Action-bar speed meter for staff. Sampling runs on the player's own region thread (one task per player, not
 * one global task touching everyone), so Folia's threading rules are respected and a player who is idle costs
 * one cheap tick task.
 *
 * <p>The meter is a pure display: it never touches the player's position, so it cannot interfere with
 * movement or anti-cheat.
 */
final class SpeedMeter {
    private final ModuleContext ctx;
    private final Supplier<StaffConfig> config;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        final Player player;
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile List<SpeedSamples.Sample> window = List.of();
        volatile TaskHandle task = TaskHandle.NOOP;
        int ticks;

        Session(Player player) {
            this.player = player;
        }
    }

    SpeedMeter(ModuleContext ctx, Supplier<StaffConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    boolean enabled(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** Turns the meter on or off; returns the new state. Player thread. */
    boolean toggle(Player player) {
        Session existing = sessions.remove(player.getUniqueId());
        if (existing != null) {
            stop(existing);
            return false;
        }
        start(player);
        return true;
    }

    /** Turns the meter off if it is on. Any thread. */
    void stop(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            stop(session);
        }
    }

    private void start(Player player) {
        Session session = new Session(player);
        sessions.put(player.getUniqueId(), session);
        try {
            session.task = ctx.track(ctx.scheduler().entityTimer(player, () -> tick(session),
                    () -> sessions.remove(player.getUniqueId()), 1, 1));
        } catch (RuntimeException error) {
            sessions.remove(player.getUniqueId(), session);
            ctx.logger().warn("Could not start the speed meter of {}", player.getName(), error);
        }
    }

    /** Player thread: one position sample per tick, one action bar update per configured interval. */
    private void tick(Session session) {
        Player player = session.player;
        if (session.stopped.get() || !player.isOnline()) {
            stop(session);
            return;
        }
        Location location = player.getLocation();
        session.window = SpeedSamples.add(session.window,
                new SpeedSamples.Sample(System.currentTimeMillis(), location.getX(), location.getY(), location.getZ()));
        int interval = Math.clamp(config.get().speedMeterIntervalTicks, 1, 20);
        if (++session.ticks < interval) {
            return;
        }
        session.ticks = 0;
        double kmh = SpeedSamples.kmh(session.window);
        player.sendActionBar(ctx.messages().render(player, "staff.speed-actionbar",
                Msg.unparsed("speed", SpeedSamples.format(kmh))));
    }

    private void stop(Session session) {
        if (session.stopped.compareAndSet(false, true)) {
            session.task.cancel();
        }
        sessions.remove(session.player.getUniqueId(), session);
    }

    /** The tracked average, or the player's current velocity when the meter is off. Target thread. */
    CompletableFuture<Double> measure(Player target) {
        return ctx.scheduler().supplyOnEntity(target, () -> {
            Session session = sessions.get(target.getUniqueId());
            if (session != null) {
                return SpeedSamples.kmh(session.window);
            }
            // 20 ticks per second; velocity is blocks per tick.
            return target.getVelocity().length() * 20 * 3.6;
        });
    }

    /** Module disable: no timer may run after this. */
    void shutdown() {
        for (Session session : List.copyOf(sessions.values())) {
            stop(session);
        }
        sessions.clear();
    }
}
