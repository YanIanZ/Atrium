package dev.yanianz.atrium.module.player;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.user.UserComponentType;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.core.storage.Database;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.GameMode;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Timed flight ({@code /tempfly}): every player has a balance of flight seconds in the shared database. While it
 * is switched on the player holds a flight claim ({@link #OWNER}) and the balance only goes down while they are
 * actually flying in survival or adventure mode. At zero the claim is released and the fall that follows deals no
 * damage. The running balance lives in memory on the player's own thread and is written back every
 * {@link #SAVE_EVERY_SECONDS} seconds, on toggle, on quit and on disable.
 */
final class TimedFlight implements Listener, TaskHandle {
    static final String OWNER = "player:tempfly";
    private static final int SAVE_EVERY_SECONDS = 30;
    private static final long MAX_SECONDS = 31_536_000L;
    private static final long FALL_GRACE_MILLIS = 15_000L;
    private static final Set<Long> WARN_AT = Set.of(300L, 60L, 30L, 10L, 5L, 4L, 3L, 2L, 1L);

    /** Persisted balance. {@code active} = the player left it switched on. */
    record State(long seconds, boolean active) {
        static final State EMPTY = new State(0L, false);
    }

    /** A player whose timed flight is switched on here. Touched only on the player's thread. */
    private static final class Session {
        long seconds;
        int unsaved;
        boolean suspended;
        TaskHandle task;
    }

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<PlayerConfig> config;
    private final FlightManager flight;
    private final Database db;
    final UserComponentType<State> component;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fallGrace = new ConcurrentHashMap<>();
    private volatile boolean cancelled;

    TimedFlight(ModuleContext ctx, ConfigHandle<PlayerConfig> config, FlightManager flight) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.flight = flight;
        this.db = ctx.database();
        this.component = new UserComponentType<>("player:tempfly", this::load, this::save, () -> State.EMPTY);
    }

    void register() {
        ctx.component(component);
        ctx.track(this);
        ctx.listen(this);
        ctx.permission("atrium.tempfly", PermissionDefault.TRUE, "Use your timed flight balance with /tempfly");
        ctx.permission("atrium.tempfly.admin", PermissionDefault.OP, "Give, take or set timed flight");
        ctx.command(CommandSpec.of(Cmds.literal("tempfly", "atrium.tempfly")
                .executes(this::toggle)
                .then(Commands.literal("time").executes(this::time))
                .then(admin("give"))
                .then(admin("take"))
                .then(admin("set"))
                .build(), "Timed flight: toggle, time left, and admin give/take/set", "flytime", "terbang"));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> admin(String action) {
        return Commands.literal(action)
                .requires(Cmds.permission("atrium.tempfly.admin"))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("10m", "30m", "1h", "6h", "1d")))
                                .executes(c -> change(c, action))));
    }

    // ---- storage ----

    private CompletableFuture<State> load(UUID uuid) {
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT seconds, active FROM " + db.table("player_tempfly") + " WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new State(Math.max(0L, rs.getLong(1)), rs.getBoolean(2)) : null;
                }
            }
        });
    }

    private CompletableFuture<Void> save(UUID uuid, State state) {
        String sql = db.dialect().upsert(db.table("player_tempfly"), "uuid, seconds, active", "?, ?, ?", "uuid",
                "seconds, active");
        return db.query(c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                Database.bind(ps, uuid.toString(), state.seconds(), state.active());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private State state(Player player) {
        return ctx.users().user(player).get(component);
    }

    private void store(Player player, long seconds, boolean active) {
        ctx.users().user(player).update(component, old -> new State(Math.max(0L, seconds), active));
    }

    // ---- commands ----

    private int toggle(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ctx.scheduler().entity(player, () -> {
            if (cancelled || !player.isOnline()) {
                return;
            }
            Session session = sessions.get(player.getUniqueId());
            if (session != null) {
                stop(player, session, false);
                send(player, "tempfly-off", time(session.seconds));
                return;
            }
            if (!config.get().timedFlightEnabled) {
                send(player, "tempfly-disabled-here");
                return;
            }
            long seconds = state(player).seconds();
            if (seconds <= 0) {
                send(player, "tempfly-empty");
                return;
            }
            if (blocked(player)) {
                send(player, "tempfly-blocked-world");
                return;
            }
            start(player, seconds);
            send(player, "tempfly-on", time(seconds));
        }, null);
        return Cmds.OK;
    }

    private int time(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ctx.scheduler().entity(player, () -> {
            if (cancelled || !player.isOnline()) {
                return;
            }
            Session session = sessions.get(player.getUniqueId());
            long seconds = session != null ? session.seconds : state(player).seconds();
            send(player, session != null ? "tempfly-time-on" : "tempfly-time", time(seconds));
        }, null);
        return Cmds.OK;
    }

    private int change(CommandContext<CommandSourceStack> c, String action) throws CommandSyntaxException {
        Player target = cmds.argPlayer(c, "target");
        CommandSender sender = c.getSource().getSender();
        Optional<Duration> parsed = Durations.parse(StringArgumentType.getString(c, "duration"));
        if (parsed.isEmpty() || parsed.get().isNegative()) {
            throw cmds.fail(c.getSource(), "player.tempfly-bad-duration");
        }
        long amount = Math.min(MAX_SECONDS, parsed.get().toSeconds());
        ctx.scheduler().entity(target, () -> {
            if (cancelled || !target.isOnline()) {
                reply(sender, "target-left");
                return;
            }
            Session session = sessions.get(target.getUniqueId());
            long before = session != null ? session.seconds : state(target).seconds();
            long after = switch (action) {
                case "give" -> Math.min(MAX_SECONDS, before + amount);
                case "take" -> Math.max(0L, before - amount);
                default -> amount;
            };
            if (session != null) {
                session.seconds = after;
                session.unsaved = 0;
                if (after <= 0) {
                    stop(target, session, true);
                } else {
                    store(target, after, true);
                }
            } else {
                store(target, after, false);
            }
            send(target, "tempfly-changed", time(after));
            if (!sender.equals(target)) {
                reply(sender, "tempfly-changed-other", TagResolver.resolver(
                        Msg.unparsed("player", Msg.shownName(target)), time(after)));
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    /**
     * {@code /fly <player> <duration>}: adds the duration to the target's timed flight and switches it on, so the
     * target flies now and stops when the time is used up. Refused like {@code /tempfly} where timed flight is off
     * or the world blocks it; the time is still added in that case.
     */
    int grantAndStart(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.argPlayer(c, "target");
        CommandSender sender = c.getSource().getSender();
        Optional<Duration> parsed = Durations.parse(StringArgumentType.getString(c, "duration"));
        if (parsed.isEmpty() || parsed.get().isNegative() || parsed.get().isZero()) {
            throw cmds.fail(c.getSource(), "player.tempfly-bad-duration");
        }
        long amount = Math.min(MAX_SECONDS, parsed.get().toSeconds());
        ctx.scheduler().entity(target, () -> {
            if (cancelled || !target.isOnline()) {
                reply(sender, "target-left");
                return;
            }
            Session session = sessions.get(target.getUniqueId());
            long before = session != null ? session.seconds : state(target).seconds();
            long after = Math.min(MAX_SECONDS, before + amount);
            if (session != null) {
                session.seconds = after;
                session.unsaved = 0;
                store(target, after, true);
            } else {
                store(target, after, false);
                if (config.get().timedFlightEnabled && !blocked(target)) {
                    start(target, after);
                }
            }
            send(target, sessions.containsKey(target.getUniqueId()) ? "tempfly-on" : "tempfly-changed", time(after));
            if (!sender.equals(target)) {
                reply(sender, "tempfly-changed-other", TagResolver.resolver(
                        Msg.unparsed("player", Msg.shownName(target)), time(after)));
            }
        }, () -> reply(sender, "target-left"));
        return Cmds.OK;
    }

    // ---- session ----

    /** Player's thread. */
    private void start(Player player, long seconds) {
        Session session = new Session();
        session.seconds = seconds;
        session.suspended = blocked(player);
        sessions.put(player.getUniqueId(), session);
        store(player, seconds, true);
        if (!session.suspended) {
            flight.claim(player, OWNER);
        }
        session.task = ctx.track(ctx.scheduler().entityTimer(player, () -> tick(player, session),
                () -> sessions.remove(player.getUniqueId(), session), 20L, 20L));
    }

    /** Player's thread: switches timed flight off and writes the balance. */
    private void stop(Player player, Session session, boolean expired) {
        if (!sessions.remove(player.getUniqueId(), session)) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        if (player.isFlying() && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR) {
            fallGrace.put(player.getUniqueId(), System.currentTimeMillis() + FALL_GRACE_MILLIS);
        }
        flight.release(player, OWNER);
        store(player, expired ? 0L : session.seconds, false);
    }

    /** Player's thread, once a second while timed flight is on. */
    private void tick(Player player, Session session) {
        if (cancelled || sessions.get(player.getUniqueId()) != session) {
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        GameMode mode = player.getGameMode();
        if (session.suspended || !player.isFlying() || mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return;
        }
        session.seconds--;
        session.unsaved++;
        if (session.seconds <= 0) {
            stop(player, session, true);
            send(player, "tempfly-expired");
            return;
        }
        if (WARN_AT.contains(session.seconds)) {
            ctx.messages().actionBar(player, "player.tempfly-warning", time(session.seconds));
        }
        if (session.unsaved >= SAVE_EVERY_SECONDS) {
            session.unsaved = 0;
            store(player, session.seconds, true);
        }
    }

    private boolean blocked(Player player) {
        List<String> worlds = config.get().timedFlightBlockedWorlds;
        if (worlds == null || worlds.isEmpty()) {
            return false;
        }
        String name = player.getWorld().getName();
        for (String world : worlds) {
            if (world != null && world.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // ---- events ----

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // The user component is loaded during login; resume a balance that was left switched on.
        ctx.scheduler().entityLater(player, () -> {
            if (cancelled || !player.isOnline() || sessions.containsKey(player.getUniqueId())) {
                return;
            }
            if (!config.get().timedFlightEnabled || !player.hasPermission("atrium.tempfly")) {
                return;
            }
            State state = state(player);
            if (state.active() && state.seconds() > 0) {
                start(player, state.seconds());
                send(player, "tempfly-resumed", time(state.seconds()));
            }
        }, null, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        fallGrace.remove(player.getUniqueId());
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (session.task != null) {
            session.task.cancel();
        }
        // Still "active": the next server (or the next join) resumes it. FlightManager drops the claim itself.
        store(player, session.seconds, true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        boolean nowBlocked = blocked(player);
        if (nowBlocked == session.suspended) {
            return;
        }
        session.suspended = nowBlocked;
        if (nowBlocked) {
            if (player.isFlying()) {
                fallGrace.put(player.getUniqueId(), System.currentTimeMillis() + FALL_GRACE_MILLIS);
            }
            flight.release(player, OWNER);
            send(player, "tempfly-blocked-world");
        } else {
            flight.claim(player, OWNER);
        }
    }

    /** The fall right after timed flight ends is free. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Long until = fallGrace.remove(player.getUniqueId());
        if (until != null && until >= System.currentTimeMillis()) {
            event.setCancelled(true);
        }
    }

    // ---- lifecycle ----

    /** Module disable: write every running balance; flight claims are cleared by FlightManager. */
    @Override
    public void cancel() {
        cancelled = true;
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            Player player = org.bukkit.Bukkit.getPlayer(entry.getKey());
            Session session = entry.getValue();
            if (session.task != null) {
                session.task.cancel();
            }
            if (player != null) {
                try {
                    store(player, session.seconds, true);
                } catch (RuntimeException e) {
                    ctx.logger().warn("Could not save the timed flight of {}", player.getName(), e);
                }
            }
        }
        sessions.clear();
        fallGrace.clear();
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    // ---- helpers ----

    private static TagResolver time(long seconds) {
        return Msg.unparsed("time", Durations.format(Duration.ofSeconds(Math.max(0L, seconds)), 2));
    }

    private void send(Player player, String key, TagResolver... tags) {
        ctx.messages().send(player, "player." + key, tags);
    }

    private void reply(CommandSender recipient, String key, TagResolver... tags) {
        if (cancelled) {
            return;
        }
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> {
                if (!cancelled) {
                    ctx.messages().send(player, "player." + key, tags);
                }
            }, null);
        } else {
            ctx.messages().send(recipient, "player." + key, tags);
        }
    }
}
