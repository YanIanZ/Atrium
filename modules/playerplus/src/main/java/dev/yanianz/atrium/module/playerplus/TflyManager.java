package dev.yanianz.atrium.module.playerplus;

import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jspecify.annotations.Nullable;

/**
 * Temporary flight: claims flight through {@link FlightService} while the persisted countdown runs and
 * persists only the remaining duration, so time offline never counts. The countdown is driven by a
 * one-second entity timer that only exists while a player actually has temporary flight.
 */
final class TflyManager implements Listener {
    static final String OWNER = "playerplus:tfly";

    private final ModuleContext ctx;
    private final ConfigHandle<PlayerPlusConfig> config;
    private final PlayerPlusStore store;
    private final @Nullable FlightService flight;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    TflyManager(ModuleContext ctx, ConfigHandle<PlayerPlusConfig> config, PlayerPlusStore store,
            @Nullable FlightService flight) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.flight = flight;
    }

    boolean available() {
        return flight != null;
    }

    long maxMillis() {
        return Math.max(1, config.get().tfly.maxSeconds) * 1000L;
    }

    /** Remaining time from the live session, or the persisted value while no session is running. */
    long remaining(Player player) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return ctx.users().user(player).get(store.component).tflyRemainingMillis();
        }
        return Math.max(0, session.deadline - System.currentTimeMillis());
    }

    /**
     * Gives exactly {@code totalMillis} of temporary flight and restarts the countdown from now. Must be
     * called on the player's thread.
     */
    void setRemaining(Player player, long totalMillis) {
        AtriumUser user = ctx.users().user(player);
        long total = Math.min(Math.max(0, totalMillis), maxMillis());
        UUID uuid = player.getUniqueId();
        Session previous = sessions.remove(uuid);
        if (previous != null) {
            previous.cancel();
        }
        if (total <= 0) {
            user.update(store.component, state -> state.withTflyRemaining(0));
            if (flight != null) {
                flight.release(player, OWNER);
            }
            return;
        }
        long deadline = System.currentTimeMillis() + total;
        Session session = new Session(deadline);
        sessions.put(uuid, session);
        user.update(store.component, state -> state.withTflyRemaining(total));
        if (flight != null) {
            flight.claim(player, OWNER);
        }
        session.task = ctx.track(ctx.scheduler().entityTimer(player, () -> tick(player), null, 20, 20));
    }

    /** Extends a running countdown (or starts one) and returns the resulting total. Player's thread. */
    long extend(Player player, long addedMillis) {
        long current = remaining(player);
        long total = TflyMath.extend(current, addedMillis, maxMillis());
        setRemaining(player, total);
        return total;
    }

    /** Ends the countdown now with a safe landing. Player's thread. */
    void stop(Player player, boolean grace) {
        UUID uuid = player.getUniqueId();
        Session session = sessions.remove(uuid);
        if (session != null) {
            session.cancel();
        }
        ctx.users().user(player).update(store.component, state -> state.withTflyRemaining(0));
        if (flight != null) {
            flight.release(player, OWNER);
        }
        if (grace) {
            SafeLanding.apply(player, config.get().tfly.graceSeconds);
        }
    }

    private void tick(Player player) {
        UUID uuid = player.getUniqueId();
        Session session = sessions.get(uuid);
        if (session == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long left = Math.max(0, session.deadline - now);
        int warningSeconds = Math.max(0, config.get().tfly.warningSeconds);
        if (TflyMath.shouldWarn(left, session.warned, warningSeconds * 1000L)) {
            session.warned = true;
            ctx.messages().actionBar(player, "playerplus.tfly-warning",
                    Msg.number("seconds", Math.max(1, (left + 999) / 1000)));
        }
        ctx.users().user(player).update(store.component, state -> state.withTflyRemaining(left));
        if (left <= 0) {
            sessions.remove(uuid, session);
            session.cancel();
            if (flight != null) {
                flight.release(player, OWNER);
            }
            SafeLanding.apply(player, config.get().tfly.graceSeconds);
            ctx.messages().send(player, "playerplus.tfly-ended");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // The join event runs on the player's region thread; reading the loaded component is safe.
        ctx.scheduler().entity(player, () -> {
            long stored = ctx.users().user(player).get(store.component).tflyRemainingMillis();
            if (stored <= 0 || flight == null) {
                return;
            }
            setRemaining(player, stored);
            ctx.messages().send(player, "playerplus.tfly-resumed", Msg.duration("time", Duration.ofMillis(stored)));
        }, null);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        session.cancel();
        long left = Math.max(0, session.deadline - System.currentTimeMillis());
        try {
            ctx.users().cached(player.getUniqueId())
                    .ifPresent(user -> user.update(store.component, state -> state.withTflyRemaining(left)));
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not persist the temporary flight of {} on quit", player.getName(), e);
        }
    }

    void disable() {
        for (Session session : sessions.values()) {
            session.cancel();
        }
        sessions.clear();
    }

    private static final class Session {
        final long deadline;
        volatile boolean warned;
        volatile @Nullable TaskHandle task;

        Session(long deadline) {
            this.deadline = deadline;
        }

        void cancel() {
            TaskHandle current = task;
            if (current != null) {
                current.cancel();
            }
        }
    }
}
