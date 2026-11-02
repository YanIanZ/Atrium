package dev.yanianz.atrium.module.playtime;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.afk.AfkService;
import dev.yanianz.atrium.api.playtime.PlaytimeAdminService;
import dev.yanianz.atrium.api.playtime.PlaytimeService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Active playtime with per-day buckets, cached top lists and durable playtime rewards.
 *
 * <p>AFK time is excluded while the optional {@code afk} module publishes {@code AfkService}; if it is
 * missing, total online time is counted instead.
 */
public final class PlaytimeModule implements AtriumModule, Listener, PlaytimeService, PlaytimeAdminService {
    private static final String PERM_PLAYTIME = "atrium.playtime";
    private static final String PERM_OTHERS = "atrium.playtime.others";
    private static final String PERM_TOP = "atrium.playtimetop";
    private static final String PERM_EDIT = "atrium.editplaytime";
    private static final long CONFIRM_MILLIS = 30_000L;

    /** A destructive playtime edit waiting for the sender to repeat the command (or click Confirm). */
    private record PendingEdit(PlaytimeEdit.Mode mode, long seconds, long expiresAt) {}

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<PlaytimeConfig> config;
    private PlaytimeStore store;
    private volatile ZoneId zone;
    private volatile List<PlaytimeRewards.Spec> rewards = List.of();
    private final Map<UUID, PlaytimeSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> tails = new HashMap<>();
    private final Map<String, PendingEdit> pendingEdits = new ConcurrentHashMap<>();
    private final Set<String> warnedRewards = ConcurrentHashMap.newKeySet();
    private volatile Map<PlaytimePeriod, List<PlaytimeStore.TopEntry>> tops = Map.of();
    private final AtomicBoolean refreshing = new AtomicBoolean();

    @Override
    public String id() {
        return "playtime";
    }

    @Override
    public String description() {
        return "Active playtime, leaderboards and playtime rewards";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("afk");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.cmds = context.commands();
        this.config = ctx.config(PlaytimeConfig.class, PlaytimeConfig::new);
        validate(config.get());
        this.zone = ZoneId.of(config.get().timeZone.strip());
        this.rewards = parseRewards();
        ctx.migrate(PlaytimeStore.MIGRATIONS);
        this.store = new PlaytimeStore(ctx.database());
        ctx.component(store.component);
        ctx.service(PlaytimeService.class, this);
        ctx.service(PlaytimeAdminService.class, this);
        ctx.listen(this);

        ctx.permission(PERM_PLAYTIME, PermissionDefault.TRUE, "Check playtime");
        ctx.permission(PERM_OTHERS, PermissionDefault.OP, "Check another player's playtime");
        ctx.permission(PERM_TOP, PermissionDefault.TRUE, "View playtime leaderboards");
        ctx.permission(PERM_EDIT, PermissionDefault.OP, "Set, add or take a player's playtime");

        registerCommands();

        for (Player online : Bukkit.getOnlinePlayers()) {
            startSession(online);
        }
        refreshTop();
        long sampleTicks = Math.max(1, config.get().sampleSeconds) * 20L;
        ctx.track(ctx.scheduler().globalTimer(this::sampleAll, 20L * 30, sampleTicks));
        long rewardTicks = Math.max(1, config.get().rewardCheckSeconds) * 20L;
        ctx.track(ctx.scheduler().globalTimer(this::rewardAll, 20L * 10, rewardTicks));
        long topTicks = Math.max(1, config.get().leaderboardRefreshSeconds) * 20L;
        ctx.track(ctx.scheduler().globalTimer(this::refreshTop, topTicks, topTicks));
    }

    @Override
    public void disable() {
        List<CompletableFuture<?>> flushes = new ArrayList<>();
        for (PlaytimeSession session : sessions.values()) {
            long seconds = session.finish(config.get().sampleSeconds * 2L);
            if (seconds > 0) {
                flushes.add(store.addSeconds(session.uuid(), session.day(), seconds)
                        .exceptionally(t -> {
                            ctx.logger().warn("Saving playtime of {} during shutdown failed", session.uuid(), t);
                            return null;
                        }));
            }
        }
        sessions.clear();
        if (!flushes.isEmpty()) {
            try {
                CompletableFuture.allOf(flushes.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                ctx.logger().warn("Not all playtime was saved during shutdown", e);
            }
        }
    }

    @Override
    public void reload() throws Exception {
        validate(config.get());
        this.rewards = parseRewards();
        this.zone = ZoneId.of(config.get().timeZone.strip());
        refreshTop();
    }

    private void validate(PlaytimeConfig cfg) {
        if (cfg.sampleSeconds < 5 || cfg.sampleSeconds > 300
                || cfg.rewardCheckSeconds < 10 || cfg.rewardCheckSeconds > 600
                || cfg.maxGrantsPerCheck < 1 || cfg.maxGrantsPerCheck > 1_000
                || cfg.leaderboardRefreshSeconds < 30 || cfg.leaderboardRefreshSeconds > 3600
                || cfg.leaderboardEntries < 10 || cfg.leaderboardEntries > 10_000
                || cfg.pageSize < 1 || cfg.pageSize > 100) {
            throw new IllegalArgumentException("Invalid playtime intervals, grant cap, leaderboard size or page size");
        }
        String id = cfg.timeZone == null ? "" : cfg.timeZone.strip();
        try {
            ZoneId.of(id);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid playtime timezone: " + cfg.timeZone, e);
        }
        parseRewards(cfg);
    }

    private List<PlaytimeRewards.Spec> parseRewards() {
        return parseRewards(config.get());
    }

    private List<PlaytimeRewards.Spec> parseRewards(PlaytimeConfig cfg) {
        if (cfg.rewards == null) {
            return List.of();
        }
        List<PlaytimeRewards.Spec> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (PlaytimeConfig.Reward reward : cfg.rewards) {
            PlaytimeRewards.Spec spec = PlaytimeRewards.spec(reward);
            if (!ids.add(spec.id().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate playtime reward id: " + spec.id());
            }
            result.add(spec);
        }
        return List.copyOf(result);
    }

    // ---- commands ----

    private void registerCommands() {
        ctx.command(CommandSpec.of(Cmds.literal("playtime", PERM_PLAYTIME)
                .executes(c -> {
                    Player self = cmds.player(c.getSource());
                    show(c.getSource().getSender(), self.getUniqueId(), self.getName(), true);
                    return Cmds.OK;
                })
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(Cmds.permission(PERM_OTHERS))
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::other))
                .build(), "Show playtime", "ptime", "cplaytime"));

        ctx.command(CommandSpec.of(Cmds.literal("editplaytime", PERM_EDIT)
                .executes(this::editUsage)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .suggests((c, builder) -> {
                                    for (PlaytimeEdit.Mode mode : PlaytimeEdit.Mode.values()) {
                                        if (mode.key().startsWith(builder.getRemainingLowerCase())) {
                                            builder.suggest(mode.key());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("duration", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("30m", "1h", "6h", "1d", "7d")))
                                        .executes(c -> edit(c, false))
                                        .then(Cmds.literal("confirm").executes(c -> edit(c, true))))))
                .build(), "Set, add or take an offline player's playtime"));

        ctx.command(CommandSpec.of(Cmds.literal("playtimetop", PERM_TOP)
                .executes(c -> top(c, PlaytimePeriod.ALL, 1))
                .then(Commands.argument("period", StringArgumentType.word())
                        .suggests((c, builder) -> {
                            for (String value : List.of("day", "week", "month", "all")) {
                                if (value.startsWith(builder.getRemainingLowerCase())) {
                                    builder.suggest(value);
                                }
                            }
                            return builder.buildFuture();
                        })
                        .executes(c -> top(c, period(c), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(c -> top(c, period(c), IntegerArgumentType.getInteger(c, "page")))))
                .build(), "Playtime leaderboard", "ptop"));
    }

    private PlaytimePeriod period(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return PlaytimePeriod.parse(StringArgumentType.getString(c, "period"))
                .orElseThrow(() -> cmds.fail(c.getSource(), "playtime.invalid-period"));
    }

    private int other(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        ctx.users().resolveAccount(name, false).whenComplete((found, failure) -> onSender(sender, () -> {
            if (failure != null) {
                ctx.logger().warn("Resolving playtime target {} failed", name, failure);
                sendCore(sender, "core.error");
                return;
            }
            if (found.isEmpty()) {
                sendCore(sender, "core.user-not-found", Msg.unparsed("name", name));
                return;
            }
            show(sender, found.get().uuid(), found.get().name(), false);
        }));
        return Cmds.OK;
    }

    // ---- /editplaytime ----

    private int editUsage(CommandContext<CommandSourceStack> c) {
        send(c.getSource().getSender(), "edit-usage");
        return Cmds.OK;
    }

    private int edit(CommandContext<CommandSourceStack> c, boolean confirmed) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        String name = StringArgumentType.getString(c, "player");
        PlaytimeEdit.Mode mode = PlaytimeEdit.Mode.parse(StringArgumentType.getString(c, "mode"))
                .orElseThrow(() -> cmds.fail(source, "playtime.invalid-mode"));
        String rawDuration = StringArgumentType.getString(c, "duration");
        Duration duration = Durations.parse(rawDuration)
                .orElseThrow(() -> cmds.fail(source, "core.invalid-duration", Msg.unparsed("input", rawDuration)));
        long seconds = duration.getSeconds();

        ctx.users().resolveAccount(name, true).whenComplete((found, failure) -> onSender(sender, () -> {
            if (failure != null) {
                ctx.logger().warn("Resolving the /editplaytime target {} failed", name, failure);
                sendCore(sender, "core.error");
                return;
            }
            if (found.isEmpty()) {
                sendCore(sender, "core.user-not-found", Msg.unparsed("name", name));
                return;
            }
            UUID target = found.get().uuid();
            String targetName = found.get().name();
            if (mode != PlaytimeEdit.Mode.ADD && !confirm(sender, target, targetName, mode, seconds, confirmed)) {
                return;
            }
            CompletableFuture<Void> change = switch (mode) {
                case SET -> setPlaytime(target, Duration.ofSeconds(seconds));
                case ADD -> addPlaytime(target, Duration.ofSeconds(seconds));
                case TAKE -> takePlaytime(target, seconds);
            };
            change.thenCompose(ignored -> store.total(target)).whenComplete((newTotal, error) -> onSender(sender, () -> {
                if (error != null) {
                    ctx.logger().error("Editing the playtime of {} ({}) failed", targetName, target, unwrap(error));
                    sendCore(sender, "core.error");
                    return;
                }
                send(sender, "edit-done",
                        Msg.unparsed("player", targetName),
                        Msg.unparsed("mode", mode.key()),
                        Msg.duration("amount", duration),
                        Msg.duration("total", Duration.ofSeconds(newTotal == null ? 0 : newTotal)));
            }));
        }));
        return Cmds.OK;
    }

    /**
     * Two-step confirmation for set/take. The first call stores the pending change and sends a clickable
     * confirmation; repeating the same command (or using the {@code confirm} literal) within 30 seconds
     * applies it. Console senders use the same path.
     *
     * @return true when the change may run now
     */
    private boolean confirm(CommandSender sender, UUID target, String targetName, PlaytimeEdit.Mode mode,
            long seconds, boolean confirmed) {
        String key = sender.getName() + "|" + target + "|" + mode.key() + "|" + seconds;
        long now = System.currentTimeMillis();
        PendingEdit pending = pendingEdits.get(key);
        if (pending != null && pending.expiresAt() < now) {
            pendingEdits.remove(key, pending);
            pending = null;
        }
        if (confirmed && pending == null) {
            send(sender, "edit-no-pending");
            return false;
        }
        if (pending == null) {
            pendingEdits.put(key, new PendingEdit(mode, seconds, now + CONFIRM_MILLIS));
            Component button = ctx.messages().render(sender, "playtime.confirm-button")
                    .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "playtime.confirm-hover")));
            if (targetName.matches("[A-Za-z0-9_]{1,16}")) {
                button = button.clickEvent(ClickEvent.runCommand(
                        "/editplaytime " + targetName + " " + mode.key() + " " + seconds + " confirm"));
            }
            send(sender, "edit-confirm",
                    Msg.unparsed("player", targetName),
                    Msg.unparsed("mode", mode.key()),
                    Msg.duration("amount", Duration.ofSeconds(seconds)),
                    Msg.number("seconds", CONFIRM_MILLIS / 1000),
                    Msg.component("confirm", button));
            return false;
        }
        pendingEdits.remove(key, pending);
        return true;
    }

    /** Sets the absolute total as one write inside the player's queue (no re-entrant enqueue). */
    private CompletableFuture<Void> writeAbsolute(UUID player, long seconds) {
        PlaytimeSession session = sessions.get(player);
        long[] before = session == null ? new long[] {0, 0} : session.progressSnapshot();
        return store.setTotal(player, LocalDate.now(zone), seconds)
                // The jump is administrative: no interval between the old watermark and the new total
                // may be paid afterwards (an imported 2000 h must not pay the hourly reward 2000 times).
                .thenCompose(ignored -> advanceRepeating(player, seconds))
                .thenCompose(ignored -> session == null
                        ? CompletableFuture.completedFuture(null)
                        : onPlayerThread(player, () -> {
                            if (sessions.get(player) == session) {
                                session.setStoredTotal(seconds, before[0], before[1]);
                            }
                        }));
    }

    /** Takes playtime without going below zero; the read and the write share one queue entry. */
    private CompletableFuture<Void> takePlaytime(UUID player, long seconds) {
        if (seconds < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("playtime duration must not be negative"));
        }
        if (seconds == 0) {
            return CompletableFuture.completedFuture(null);
        }
        // The persisted total is the basis: seconds ticked but not flushed yet stay counted on top by the
        // sample that writes them (the session base is adjusted with the same snapshot).
        return enqueue(player, () -> store.total(player)
                .thenCompose(total -> writeAbsolute(player, PlaytimeEdit.nextTotal(total, PlaytimeEdit.Mode.TAKE, seconds))));
    }

    private void show(CommandSender sender, UUID uuid, String name, boolean self) {
        PlaytimeSession session = sessions.get(uuid);
        LocalDate today = LocalDate.now(zone);
        CompletableFuture<Long> total = session != null && session.ready()
                ? CompletableFuture.completedFuture(session.total())
                : store.total(uuid);
        CompletableFuture<Long> todays = session != null && session.ready() && today.equals(session.day())
                ? CompletableFuture.completedFuture(session.today())
                : store.secondsOn(uuid, today);
        long sessionSeconds = session != null ? session.sessionSeconds() : 0;
        total.thenCombine(todays, (all, current) -> new long[]{all, current})
                .whenComplete((values, failure) -> onSender(sender, () -> {
                    if (failure != null) {
                        ctx.logger().warn("Reading playtime of {} failed", name, failure);
                        sendCore(sender, "core.error");
                        return;
                    }
                    send(sender, self ? "self" : "other",
                            Msg.unparsed("player", name),
                            Msg.duration("total", Duration.ofSeconds(values[0])),
                            Msg.duration("session", Duration.ofSeconds(sessionSeconds)),
                            Msg.duration("today", Duration.ofSeconds(values[1])));
                }));
    }

    private int top(CommandContext<CommandSourceStack> c, PlaytimePeriod period, int page) {
        CommandSender sender = c.getSource().getSender();
        List<PlaytimeStore.TopEntry> snapshot = tops.getOrDefault(period, List.of());
        if (!tops.containsKey(period)) {
            refreshTop();
            send(sender, "top-loading");
            return Cmds.OK;
        }
        PlaytimeConfig cfg = config.get();
        long start = (long) (page - 1) * cfg.pageSize;
        if (start >= snapshot.size()) {
            send(sender, "top-empty");
            return Cmds.OK;
        }
        send(sender, "top-header", Msg.unparsed("period", period.key()), Msg.number("page", page));
        for (int i = (int) start; i < Math.min(start + cfg.pageSize, snapshot.size()); i++) {
            PlaytimeStore.TopEntry entry = snapshot.get(i);
            send(sender, "top-entry", Msg.number("rank", i + 1), Msg.unparsed("player", entry.name()),
                    Msg.duration("time", Duration.ofSeconds(entry.seconds())));
        }
        return Cmds.OK;
    }

    // ---- sampling ----

    private void sampleAll() {
        int maxSeconds = Math.max(1, config.get().sampleSeconds) * 2;
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            try {
                ctx.scheduler().entity(player, () -> sample(player, maxSeconds), null);
            } catch (Throwable t) {
                ctx.logger().warn("Scheduling playtime sample for {} failed", player.getName(), t);
            }
        }
    }

    private void sample(Player player, int maxSeconds) {
        PlaytimeSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        // The probe touches the player (AFK state), so it stays on the entity thread. The tick and its
        // write are queued together, so an administrative set/add for the same player is totally ordered
        // with the periodic samples instead of racing them.
        boolean active = countedAsActive(player);
        enqueue(session.uuid(), () -> {
            long seconds = session.tick(active, maxSeconds);
            if (seconds <= 0) {
                return CompletableFuture.completedFuture(null);
            }
            return store.addSeconds(session.uuid(), session.day(), seconds)
                    .exceptionally(t -> {
                        ctx.logger().warn("Saving {} seconds of playtime for {} failed", seconds, player.getName(), t);
                        return null;
                    });
        }).exceptionally(t -> {
            ctx.logger().warn("Queued playtime sample for {} failed", player.getName(), t);
            return null;
        });
    }

    /**
     * Active-time probe. When the optional {@code afk} module is enabled its service is used, so AFK
     * players stop accumulating playtime. Without it, total online time is counted.
     */
    private boolean countedAsActive(Player player) {
        AfkService afk = ctx.services().get(AfkService.class).orElse(null);
        return afk == null || !afk.isAfk(player.getUniqueId());
    }

    private void rewardAll() {
        for (UUID uuid : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            try {
                ctx.scheduler().entity(player, () -> {
                    PlaytimeSession session = sessions.get(uuid);
                    if (session != null) {
                        checkRewards(player, session);
                    }
                }, null);
            } catch (Throwable t) {
                ctx.logger().warn("Scheduling reward check for {} failed", player.getName(), t);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        startSession(event.getPlayer());
    }

    private void startSession(Player player) {
        UUID uuid = player.getUniqueId();
        PlaytimeSession session = new PlaytimeSession(uuid, zone);
        if (sessions.putIfAbsent(uuid, session) != null) {
            return;
        }
        // The load runs in the per-player queue together with every administrative write: a set/add can
        // therefore never be counted twice (the read either happens before it and the write applies on top,
        // or after it and already contains the change).
        enqueue(uuid, () -> store.total(uuid)
                .thenCombine(store.secondsOn(uuid, LocalDate.now(zone)), (all, current) -> new long[] {all, current})
                .thenCompose(values -> {
                    session.loaded(values[0], values[1]);
                    // Rewards are checked once the stored total is known, so a missed check while offline
                    // is not lost.
                    firstRewardCheck(player, session);
                    return CompletableFuture.<Void>completedFuture(null);
                })
                .exceptionally(t -> {
                    ctx.logger().error("Cannot load playtime of {}; not counting this session",
                            player.getName(), t);
                    sessions.remove(uuid, session);
                    return null;
                }));
    }

    private void firstRewardCheck(Player player, PlaytimeSession session) {
        try {
            ctx.scheduler().entity(player, () -> {
                if (player.isOnline() && sessions.get(player.getUniqueId()) == session) {
                    checkRewards(player, session);
                }
            }, null);
        } catch (Throwable t) {
            ctx.logger().warn("Scheduling the first reward check for {} failed", player.getName(), t);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlaytimeSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        long seconds = session.finish(config.get().sampleSeconds * 2L);
        if (seconds > 0) {
            store.addSeconds(session.uuid(), session.day(), seconds)
                    .exceptionally(t -> {
                        ctx.logger().warn("Saving final playtime of {} failed", player.getName(), t);
                        return null;
                    });
        }
    }

    // ---- rewards ----

    private void checkRewards(Player player, PlaytimeSession session) {
        if (!session.ready()) {
            return;
        }
        long total = session.total();
        long today = session.today();
        LocalDate day = session.day();
        // Permission checks happen on the player's own thread, before the delivery chain moves to I/O.
        Set<String> allowed = new HashSet<>();
        for (PlaytimeRewards.Spec spec : rewards) {
            if (spec.permission().isEmpty() || player.hasPermission(spec.permission())) {
                allowed.add(spec.id());
            }
        }
        enqueue(session.uuid(), () -> runRewards(player, total, today, day, allowed));
    }

    private CompletableFuture<Void> runRewards(Player player, long total, long today, LocalDate day,
            Set<String> allowed) {
        return store.pending(player.getUniqueId()).thenCompose(pending -> {
            Map<String, PlaytimeRewards.Spec> byId = new HashMap<>();
            for (PlaytimeRewards.Spec spec : rewards) {
                byId.put(spec.id(), spec);
            }
            Set<String> blocked = new HashSet<>();
            CompletableFuture<Void> retry = CompletableFuture.completedFuture(null);
            for (PlaytimeStore.PendingClaim claim : pending) {
                PlaytimeRewards.Spec spec = byId.get(claim.rewardId());
                if (spec == null) {
                    warnOnce("pending:" + claim.rewardId(), "Playtime claim {} is pending but its reward no longer exists; leaving it for an administrator", claim.rewardId());
                    blocked.add(claim.rewardId());
                    continue;
                }
                // A pending marker is retried before new grants: this is the crash window between "claimed"
                // and "confirmed delivered". Only the steps that did not finish yet are repeated.
                retry = retry.thenCompose(v -> deliverAndComplete(player, spec, claim.watermark())
                        .thenAccept(ok -> {
                            if (!ok) {
                                blocked.add(spec.id());
                            }
                        }));
            }
            return retry.thenCompose(v -> {
                CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
                for (PlaytimeRewards.Spec spec : rewards) {
                    if (!allowed.contains(spec.id()) || blocked.contains(spec.id())) {
                        continue;
                    }
                    chain = chain.thenCompose(ignored -> planSingle(player, spec, total, today, day)
                            .exceptionally(t -> {
                                ctx.logger().warn("Planning playtime reward {} for {} failed", spec.id(), player.getName(), t);
                                return null;
                            }));
                }
                return chain;
            });
        }).exceptionally(t -> {
            ctx.logger().warn("Playtime reward check for {} failed", player.getName(), t);
            return null;
        });
    }

    private CompletableFuture<Void> planSingle(Player player, PlaytimeRewards.Spec spec, long total, long today, LocalDate day) {
        PlaytimeProfile profile = profile(player.getUniqueId());
        PlaytimeRewards.Plan plan = PlaytimeRewards.plan(spec, profile.watermark(spec.id()), total, today, day,
                config.get().maxGrantsPerCheck);
        if (plan.empty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        if (plan.baseline() != null) {
            long mark = plan.baseline();
            chain = store.markDone(player.getUniqueId(), spec.id(), mark)
                    .thenRun(() -> remember(player.getUniqueId(), spec.id(), mark));
        }
        java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        for (PlaytimeRewards.Grant grant : plan.grants()) {
            chain = chain.thenCompose(v -> failed.get()
                    ? CompletableFuture.completedFuture(null)
                    : deliverAndComplete(player, spec, grant.mark()).thenAccept(ok -> {
                        if (!ok) {
                            failed.set(true);
                        }
                    }));
        }
        return chain;
    }

    /** @return true when delivered (or already delivered), false when the claim stays PENDING for a retry */
    private CompletableFuture<Boolean> deliverAndComplete(Player player, PlaytimeRewards.Spec spec, long mark) {
        UUID uuid = player.getUniqueId();
        return store.beginClaim(uuid, spec.id(), mark).thenCompose(claim -> {
            if (claim.isEmpty()) {
                return CompletableFuture.completedFuture(true);
            }
            PlaytimeStore.Progress progress = claim.get().progress();
            return deliver(player, spec, mark, progress)
                    .thenCompose(v -> store.completeClaim(uuid, spec.id(), mark))
                    .thenApply(v -> {
                        remember(uuid, spec.id(), mark);
                        notifyReward(player, spec.id());
                        return true;
                    });
        }).exceptionally(t -> {
            // Keep PENDING: the next check (or the next join after a restart) retries only the steps that
            // did not finish, so neither the money nor an already dispatched command is paid again.
            ctx.logger().warn("Delivering playtime reward {} (mark {}) to {} failed; will retry",
                    spec.id(), mark, player.getName(), unwrap(t));
            return false;
        });
    }

    /**
     * Delivers the outstanding steps of one claim in order (money first) and records each finished step, so
     * a retry resumes instead of repeating it.
     */
    private CompletableFuture<Void> deliver(Player player, PlaytimeRewards.Spec spec, long mark,
            PlaytimeStore.Progress progress) {
        String name = player.getName();
        if (!name.matches("[A-Za-z0-9_]{1,16}")) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid player name"));
        }
        UUID uuid = player.getUniqueId();
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (RewardDelivery.Step step : RewardDelivery.steps(spec, progress)) {
            if (step.kind() == RewardDelivery.Kind.MONEY) {
                chain = chain.thenCompose(v -> deliverMoney(player, spec, economy, mark));
            } else {
                String command = spec.commands().get(step.commandIndex()).replace("{player}", name);
                int delivered = step.commandIndex() + 1;
                chain = chain.thenCompose(v -> dispatch(command, player).thenCompose(ok -> {
                    if (!ok) {
                        warnOnce("command:" + spec.id(),
                                "Playtime reward {} command returned false: {}", spec.id(), command);
                    }
                    return store.markCommandsDone(uuid, spec.id(), mark, delivered);
                }));
            }
        }
        return chain;
    }

    /**
     * Pays the money part of a claim and records the step. Without an economy service the step is recorded
     * as skipped (warned once) instead of failing the claim, which would retry it every check forever.
     */
    private CompletableFuture<Void> deliverMoney(Player player, PlaytimeRewards.Spec spec, EconomyService economy,
            long mark) {
        UUID uuid = player.getUniqueId();
        if (RewardDelivery.moneyUnavailable(spec, economy != null)) {
            warnOnce("economy:" + spec.id(), "Playtime reward {} has money but no economy service is enabled; "
                    + "the money part is skipped permanently", spec.id());
            return store.markMoneyDone(uuid, spec.id(), mark);
        }
        return economy.deposit(uuid, spec.money(), "playtime:" + spec.id())
                .thenCompose(result -> result.success()
                        ? store.markMoneyDone(uuid, spec.id(), mark)
                        : CompletableFuture.<Void>failedFuture(
                                new IllegalStateException("Economy status " + result.status())));
    }

    private CompletableFuture<Boolean> dispatch(String command, Player player) {
        Supplier<Boolean> work = () -> {
            try {
                return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (RuntimeException e) {
                throw new CompletionException(e);
            }
        };
        return player.isOnline()
                ? ctx.scheduler().supplyOnEntity(player, work)
                : ctx.scheduler().supplyGlobal(work);
    }

    private PlaytimeProfile profile(UUID uuid) {
        Optional<AtriumUser> user = ctx.users().cached(uuid);
        if (user.isEmpty()) {
            return PlaytimeProfile.EMPTY;
        }
        try {
            return user.get().get(store.component);
        } catch (IllegalStateException e) {
            return PlaytimeProfile.EMPTY;
        }
    }

    private void remember(UUID uuid, String rewardId, long mark) {
        ctx.users().cached(uuid).ifPresent(user -> {
            try {
                user.update(store.component, profile -> profile.withWatermark(rewardId, mark));
            } catch (IllegalStateException e) {
                // The user was evicted between the cache lookup and the update; storage is authoritative.
            }
        });
    }

    private void notifyReward(Player player, String rewardId) {
        if (!player.isOnline()) {
            return;
        }
        try {
            ctx.scheduler().entity(player, () -> {
                if (player.isOnline()) {
                    send(player, "reward", Msg.unparsed("reward", rewardId));
                }
            }, null);
        } catch (Throwable t) {
            warnOnce("notify:" + rewardId, "Cannot notify a playtime reward: {}", t.toString());
        }
    }

    private synchronized CompletableFuture<Void> enqueue(UUID uuid, Supplier<CompletableFuture<Void>> work) {
        CompletableFuture<Void> next = tails.getOrDefault(uuid, CompletableFuture.completedFuture(null))
                .handle((value, failure) -> null)
                .thenCompose(v -> work.get());
        tails.put(uuid, next);
        next.whenComplete((value, failure) -> {
            synchronized (this) {
                tails.remove(uuid, next);
            }
        });
        return next;
    }

    private void refreshTop() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        PlaytimeConfig cfg = config.get();
        LocalDate today = LocalDate.now(zone);
        Map<PlaytimePeriod, List<PlaytimeStore.TopEntry>> next = new EnumMap<>(PlaytimePeriod.class);
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (PlaytimePeriod period : PlaytimePeriod.values()) {
            LocalDate from = period.start(today);
            CompletableFuture<List<PlaytimeStore.TopEntry>> future = store.top(from, cfg.leaderboardEntries);
            futures.add(future.thenAccept(entries -> next.put(period, entries)));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((value, failure) -> {
            if (failure != null) {
                ctx.logger().warn("Refreshing playtime leaderboards failed; keeping the previous snapshot", failure);
            } else {
                tops = Map.copyOf(next);
            }
            refreshing.set(false);
        });
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void warnOnce(String key, String message, Object... args) {
        if (warnedRewards.add(key)) {
            ctx.logger().warn(message, args);
        }
    }

    // ---- PlaytimeService ----

    @Override
    public CompletableFuture<Long> totalPlaytime(UUID player) {
        PlaytimeSession session = sessions.get(player);
        if (session != null) {
            // Return the in-memory value once loaded. While it loads, storage is authoritative and the
            // session base will match it (increments are only counted after the base was loaded).
            return session.ready() ? CompletableFuture.completedFuture(session.total()) : store.total(player);
        }
        return store.total(player);
    }

    @Override
    public long sessionTime(UUID player) {
        PlaytimeSession session = sessions.get(player);
        return session == null ? 0 : session.sessionSeconds();
    }

    // ---- PlaytimeAdminService ----

    @Override
    public CompletableFuture<Void> setPlaytime(UUID player, Duration total) {
        long seconds;
        try {
            seconds = requireSeconds(total);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return enqueue(player, () -> writeAbsolute(player, seconds));
    }

    @Override
    public CompletableFuture<Void> addPlaytime(UUID player, Duration amount) {
        long seconds;
        try {
            seconds = requireSeconds(amount);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        if (seconds == 0) {
            return CompletableFuture.completedFuture(null);
        }
        return enqueue(player, () -> {
            PlaytimeSession session = sessions.get(player);
            return store.addSeconds(player, LocalDate.now(zone), seconds)
                    .thenCompose(ignored -> store.total(player))
                    .thenCompose(newTotal -> advanceRepeating(player, newTotal))
                    .thenCompose(ignored -> session == null
                            ? CompletableFuture.completedFuture(null)
                            : onPlayerThread(player, () -> {
                                if (sessions.get(player) == session) {
                                    session.addStored(seconds);
                                }
                            }));
        });
    }

    /**
     * Moves every repeating reward's watermark to the water level of {@code totalSeconds}, without paying.
     * Must run after the administrative total write and before the next reward check for that player (the
     * caller is already inside the player's queue).
     */
    private CompletableFuture<Void> advanceRepeating(UUID player, long totalSeconds) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (PlaytimeRewards.Spec spec : rewards) {
            Long mark = PlaytimeRewards.advanceTo(spec, totalSeconds);
            if (mark == null) {
                continue;
            }
            chain = chain.thenCompose(ignored -> store.advanceTo(player, spec.id(), mark)
                    .thenRun(() -> remember(player, spec.id(), mark))
                    .exceptionally(t -> {
                        // The total itself is already stored; report the risk instead of pretending this
                        // step happened. The per-check grant cap bounds the backlog if a check slips through.
                        ctx.logger().warn("Cannot advance the watermark of playtime reward {} for {} to {}; "
                                + "a backlog may be paid in small steps", spec.id(), player, mark, t);
                        return null;
                    }));
        }
        return chain;
    }

    /** Seconds of a duration; negative values are rejected (also used by the unit tests). */
    static long requireSeconds(Duration duration) {
        if (duration == null) {
            throw new IllegalArgumentException("playtime duration is required");
        }
        if (duration.isNegative()) {
            throw new IllegalArgumentException("playtime duration must not be negative: " + duration);
        }
        return duration.getSeconds();
    }

    /** Runs {@code change} on the player's own thread, or inline when they are offline. */
    private CompletableFuture<Void> onPlayerThread(UUID uuid, Runnable change) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            change.run();
            return CompletableFuture.completedFuture(null);
        }
        return ctx.scheduler().supplyOnEntity(player, () -> {
            change.run();
            return null;
        }).handle((ignored, error) -> {
            if (error == null) {
                return null;
            }
            Throwable cause = unwrap(error);
            if (cause instanceof Scheduler.EntityRetiredException) {
                ctx.logger().debug("Player {} left before their playtime counter could be updated", uuid);
                return null;
            }
            throw new CompletionException(cause);
        });
    }

    // ---- message helpers ----

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private void send(CommandSender sender, String key, TagResolver... tags) {
        onSender(sender, () -> ctx.messages().send(sender, "playtime." + key, tags));
    }

    private void sendCore(CommandSender sender, String key, TagResolver... tags) {
        onSender(sender, () -> ctx.messages().send(sender, key, tags));
    }
}
