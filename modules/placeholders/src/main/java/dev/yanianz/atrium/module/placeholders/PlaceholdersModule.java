package dev.yanianz.atrium.module.placeholders;

import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.api.afk.AfkService;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.playtime.PlaytimeService;
import dev.yanianz.atrium.api.ranks.RankService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * Publishes Atrium values through a PlaceholderAPI expansion ({@code %atrium_<key>%}) when
 * PlaceholderAPI is installed, and does nothing (with one log line) when it is not.
 *
 * <p>PlaceholderAPI may call its expansion from any thread, including async chat and scoreboard
 * threads. Player and world state is therefore never read in a request: a short per-player cache is
 * refreshed on the player's own thread every second or two, cleared on quit, and requests only read
 * that cache plus service methods documented as thread-safe. Storage-backed values (balance,
 * playtime, rank) are refreshed in the background and never block a request.
 */
public final class PlaceholdersModule implements AtriumModule, Listener {
    static final String PERM_STATUS = "atrium.placeholders.status";
    private static final String PAPI_PLUGIN = "PlaceholderAPI";
    private static final long COUNT_PERIOD_TICKS = 20L;

    private record OnlineCounts(int visible, int total) {}

    private record PlaytimeSample(long totalSeconds, long sessionSeconds) {}

    private ModuleContext ctx;
    private ConfigHandle<PlaceholdersConfig> config;
    private volatile PlaceholderHook hook;
    private volatile boolean missingLogged;
    private volatile boolean stopping;

    private final ExpiringCache<PlayerSnapshot> snapshots = new ExpiringCache<>();
    private final ConcurrentHashMap<UUID, PlaytimeSample> playtimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> ranks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, BigDecimal> balances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> storageFetchedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, Long>> statistics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> statisticsFetchedAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Map<String, String>> userMeta = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, TaskHandle> timers = new ConcurrentHashMap<>();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private volatile OnlineCounts counts = new OnlineCounts(0, 0);
    private TaskHandle countTask = TaskHandle.NOOP;
    private UserMetaStore metaStore;

    @Override
    public String id() {
        return "placeholders";
    }

    @Override
    public String description() {
        return "PlaceholderAPI expansion exposing Atrium economy, playtime, rank and player values";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("afk", "economy", "moderation", "playtime", "ranks", "vanish", "warps");
    }

    @Override
    public void enable(ModuleContext context) throws Exception {
        this.ctx = context;
        this.stopping = false;
        this.config = context.config(PlaceholdersConfig.class, PlaceholdersConfig::new);
        validate(config.get());
        context.migrate(UserMetaStore.MIGRATIONS);
        this.metaStore = new UserMetaStore(ctx.database());
        context.listen(this);

        context.permission(PERM_STATUS, PermissionDefault.OP, "Show the Atrium placeholder expansion status");
        context.permission(UserMetaCommand.PERM_USE, PermissionDefault.OP,
                "Manage custom player metadata");
        context.permission(UserMetaCommand.PERM_OTHERS, PermissionDefault.OP,
                "Manage another player's custom metadata");
        context.command(CommandSpec.of(Cmds.literal("atriumplaceholders", PERM_STATUS)
                .executes(this::status)
                .build(), "Show the Atrium placeholder expansion status"));
        new UserMetaCommand(context, metaStore, this::refreshUserMeta).register();

        refreshCounts();
        countTask = context.track(context.scheduler().globalTimer(this::refreshCounts,
                COUNT_PERIOD_TICKS, COUNT_PERIOD_TICKS));

        tryHook();
        if (hook != null) {
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                startTracking(player);
            }
        }
    }

    @Override
    public void reload() throws Exception {
        validate(config.get());
        snapshots.clear();
        playtimes.clear();
        ranks.clear();
        balances.clear();
        storageFetchedAt.clear();
        statistics.clear();
        statisticsFetchedAt.clear();
        userMeta.clear();
        tryHook();
        if (hook != null) {
            for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
                startTracking(player);
            }
        }
    }

    @Override
    public void disable() {
        stopping = true;
        countTask.cancel();
        countTask = TaskHandle.NOOP;
        for (TaskHandle timer : List.copyOf(timers.values())) {
            timer.cancel();
        }
        timers.clear();
        snapshots.clear();
        playtimes.clear();
        ranks.clear();
        balances.clear();
        storageFetchedAt.clear();
        statistics.clear();
        statisticsFetchedAt.clear();
        userMeta.clear();

        PlaceholderHook current = hook;
        hook = null;
        if (current != null) {
            try {
                current.unregister();
            } catch (RuntimeException | LinkageError e) {
                ctx.logger().warn("Could not unregister the 'atrium' PlaceholderAPI expansion", e);
            }
        }
    }

    // ---- PlaceholderAPI hook ----

    private void tryHook() {
        if (stopping) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled(PAPI_PLUGIN)) {
            if (!missingLogged) {
                missingLogged = true;
                ctx.logger().info("PlaceholderAPI is not installed or not enabled; the 'atrium' placeholders "
                        + "stay inactive and all other Atrium features keep working.");
            }
            return;
        }
        if (hook != null) {
            return;
        }
        try {
            PlaceholderHook candidate = new AtriumExpansion(this, ctx.plugin().getPluginMeta().getVersion());
            if (candidate.register()) {
                hook = candidate;
                ctx.logger().info("Registered the 'atrium' PlaceholderAPI expansion with {} placeholders",
                        PlaceholderValues.KEYS.size() + ProfilePlaceholders.KEYS.size());
            } else {
                ctx.logger().warn("PlaceholderAPI refused to register the 'atrium' expansion; "
                        + "is another expansion already using that identifier?");
            }
        } catch (RuntimeException | LinkageError e) {
            ctx.logger().warn("Could not register the 'atrium' PlaceholderAPI expansion", e);
        }
    }

    // ---- PlaceholderAPI requests (any thread) ----

    String resolve(@Nullable OfflinePlayer player, String key) {
        try {
            PlaceholdersConfig cfg = config.get();
            PlaceholderValues.Texts texts =
                    new PlaceholderValues.Texts(cfg.fallback, cfg.trueText, cfg.falseText);
            UUID id = player == null ? null : player.getUniqueId();
            if (cfg.statisticsEnabled && StatisticsPlaceholders.handles(key)) {
                return StatisticsPlaceholders.resolve(key,
                        id == null ? Map.of() : statistics.getOrDefault(id, Map.of()), texts.fallback());
            }
            if (cfg.userMetaEnabled && UserMetaValues.handles(key)) {
                Optional<String> metaKey = UserMetaValues.keyFromPlaceholder(key);
                if (metaKey.isEmpty() || id == null) {
                    return texts.fallback();
                }
                String value = userMeta.getOrDefault(id, Map.of()).get(metaKey.get());
                return value == null ? texts.fallback() : PlaceholderValues.sanitizePlayerText(value);
            }
            if (ProfilePlaceholders.handles(key)) {
                return ProfilePlaceholders.resolve(key, activeProfile(player), texts.fallback());
            }
            return PlaceholderValues.resolve(key, snapshot(player), texts);
        } catch (RuntimeException e) {
            if (warned.add("resolve:" + key)) {
                ctx.logger().warn("Could not resolve the placeholder '%atrium_{}%'; returning the fallback", key, e);
            }
            return config.get().fallback == null ? "" : config.get().fallback;
        }
    }

    private PlaceholderValues.Snapshot snapshot(@Nullable OfflinePlayer player) {
        UUID id = player == null ? null : player.getUniqueId();
        Integer visible = counts.visible();
        Integer total = counts.total();
        Integer warps = warpsCount();
        if (id == null) {
            // No player: only values that are not per-player can be answered.
            return new PlaceholderValues.Snapshot(null, null, null, null, null, null, null, null,
                    null, null, null, visible, total, warps, null, null, null, null, null, null, null);
        }

        PlayerSnapshot cached = player.isOnline() ? snapshots.get(id, System.currentTimeMillis()) : null;
        String name = nameOf(id, player);

        BigDecimal balance = balanceOf(id);
        String formatted = null;
        if (balance != null) {
            EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
            if (economy != null) {
                formatted = serviceValue("formatted balance", () -> economy.format(balance));
            }
        }

        AfkService afkService = ctx.services().get(AfkService.class).orElse(null);
        Boolean afk = afkService == null ? null : serviceValue("AFK state", () -> afkService.isAfk(id));
        VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
        Boolean vanished = vanish == null ? null : serviceValue("vanish state", () -> vanish.isVanished(id));
        MuteService mute = ctx.services().get(MuteService.class).orElse(null);
        Boolean muted = mute == null ? null : serviceValue("mute state", () -> mute.isMuted(id));
        Boolean shadowMuted = mute == null ? null : serviceValue("shadow mute state", () -> mute.isShadowMuted(id));

        Long playtime = null;
        PlaytimeService playtimeService = ctx.services().get(PlaytimeService.class).orElse(null);
        if (playtimeService != null && player.isOnline()) {
            playtime = playtimeOf(id, playtimeService);
        }

        String rank = player.isOnline() ? ranks.get(id) : null;
        String rankNext = null;
        RankService rankService = ctx.services().get(RankService.class).orElse(null);
        if (rank != null && rankService != null) {
            rankNext = serviceValue("next rank", () -> nextRank(rankService, rank));
        }

        return new PlaceholderValues.Snapshot(
                name,
                cached == null ? null : cached.displayName(),
                balance,
                formatted,
                afk,
                vanished,
                muted,
                shadowMuted,
                playtime,
                rank,
                rankNext,
                visible,
                total,
                warps,
                cached == null ? null : cached.world(),
                cached == null ? null : cached.gameMode(),
                cached == null ? null : cached.health(),
                cached == null ? null : cached.maxHealth(),
                cached == null ? null : cached.foodLevel(),
                cached == null ? null : cached.experienceLevel(),
                cached == null ? null : cached.ping());
    }

    /** Cached active profile (SourbyCraft MultiProfile); never blocks, empty without the service. */
    private java.util.Optional<dev.yanianz.atrium.api.profile.ActiveProfileService.ActiveProfile> activeProfile(
            @Nullable OfflinePlayer player) {
        if (player == null) {
            return java.util.Optional.empty();
        }
        var service = ctx.services().get(dev.yanianz.atrium.api.profile.ActiveProfileService.class).orElse(null);
        if (service == null) {
            return java.util.Optional.empty();
        }
        var cached = serviceValue("active profile", () -> service.cached(player.getUniqueId()));
        return cached == null ? java.util.Optional.empty() : cached;
    }

    private String nameOf(UUID id, OfflinePlayer player) {
        return serviceValue("player name", () -> ctx.users().cached(id)
                .map(user -> user.name())
                .orElseGet(player::getName));
    }

    private BigDecimal balanceOf(UUID id) {
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        if (economy == null) {
            return null;
        }
        BigDecimal cached = serviceValue("balance", () -> economy.cachedBalance(id));
        return cached != null ? cached : balances.get(id);
    }

    private Long playtimeOf(UUID id, PlaytimeService service) {
        PlaytimeSample sample = playtimes.get(id);
        if (sample == null) {
            return null;
        }
        Long session = serviceValue("current session playtime", () -> service.sessionTime(id));
        long sessionNow = session == null ? sample.sessionSeconds() : session;
        long delta = Math.max(0L, sessionNow - sample.sessionSeconds());
        return Math.max(0L, sample.totalSeconds() + delta);
    }

    private static String nextRank(RankService service, String current) {
        List<String> ladder = service.ranks();
        int index = ladder.indexOf(current);
        return index >= 0 && index + 1 < ladder.size() ? ladder.get(index + 1) : null;
    }

    private Integer warpsCount() {
        WarpService warps = ctx.services().get(WarpService.class).orElse(null);
        return warps == null ? null : serviceValue("warp count", () -> warps.names().size());
    }

    private <T> T serviceValue(String what, Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            if (warned.add("value:" + what)) {
                ctx.logger().warn("Reading the {} for a placeholder failed; using the fallback", what, e);
            }
            return null;
        }
    }

    // ---- per-player cache (player's own thread) ----

    private void startTracking(Player player) {
        UUID id = player.getUniqueId();
        TaskHandle previous = timers.remove(id);
        if (previous != null) {
            previous.cancel();
        }
        clear(id);
        long period = Math.max(1, config.get().refreshIntervalSeconds) * 20L;
        Runnable retired = () -> stopTracking(id);
        TaskHandle timer;
        try {
            timer = ctx.scheduler().entityTimer(player, () -> refresh(player), retired, 1L, period);
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not start the placeholder cache of {}", player.getName(), e);
            return;
        }
        ctx.track(timer);
        if (timer.isDone()) {
            stopTracking(id);
        } else {
            timers.put(id, timer);
        }
    }

    private void stopTracking(UUID id) {
        TaskHandle timer = timers.remove(id);
        if (timer != null) {
            timer.cancel();
        }
        clear(id);
    }

    private void clear(UUID id) {
        snapshots.remove(id);
        playtimes.remove(id);
        ranks.remove(id);
        balances.remove(id);
        storageFetchedAt.remove(id);
        statistics.remove(id);
        statisticsFetchedAt.remove(id);
        userMeta.remove(id);
    }

    private void refresh(Player player) {
        UUID id = player.getUniqueId();
        if (!player.isOnline()) {
            stopTracking(id);
            return;
        }
        PlaceholdersConfig cfg = config.get();
        long now = System.currentTimeMillis();
        long ttl = (Math.max(1, cfg.refreshIntervalSeconds) + Math.max(0, cfg.cacheGraceSeconds)) * 1000L;
        snapshots.put(id, capture(player), now + ttl);

        if (cfg.statisticsEnabled) {
            long statsInterval = Math.max(10, cfg.statisticsRefreshSeconds) * 1000L;
            Long lastStats = statisticsFetchedAt.get(id);
            if (lastStats == null || now - lastStats >= statsInterval) {
                statisticsFetchedAt.put(id, now);
                try {
                    statistics.put(id, StatisticReader.read(player, cfg.statisticsTypedTotals));
                } catch (RuntimeException e) {
                    logBackgroundFailure("statistics", id, e);
                }
            }
        }

        long interval = Math.max(5, cfg.storageRefreshSeconds) * 1000L;
        Long last = storageFetchedAt.get(id);
        if (last == null || now - last >= interval) {
            storageFetchedAt.put(id, now);
            fetchStorageValues(player);
        }
    }

    /** Reads only state owned by this player; called on the player's own thread. */
    private static PlayerSnapshot capture(Player player) {
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        Component displayName = player.displayName();
        String plain = displayName == null ? "" : PlainTextComponentSerializer.plainText().serialize(displayName);
        return new PlayerSnapshot(
                PlaceholderValues.sanitizePlayerText(plain),
                player.getHealth(),
                maxHealth == null ? 20.0 : maxHealth.getValue(),
                player.getFoodLevel(),
                player.getLevel(),
                player.getGameMode().name().toLowerCase(Locale.ROOT),
                player.getWorld().getName(),
                player.getPing());
    }

    /**
     * Refreshes storage-backed values in the background. The futures are never joined, so a slow
     * database delays a value but never a PlaceholderAPI request.
     */
    private void fetchStorageValues(Player player) {
        UUID id = player.getUniqueId();

        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        if (economy != null) {
            try {
                if (economy.cachedBalance(id) == null) {
                    economy.balance(id).thenAccept(value -> {
                        if (value != null && timers.containsKey(id)) {
                            balances.put(id, value);
                        }
                    }).exceptionally(failure -> {
                        logBackgroundFailure("balance", id, failure);
                        return null;
                    });
                } else {
                    balances.remove(id);
                }
            } catch (RuntimeException e) {
                logBackgroundFailure("balance", id, e);
            }
        }

        PlaytimeService playtime = ctx.services().get(PlaytimeService.class).orElse(null);
        if (playtime != null) {
            try {
                playtime.totalPlaytime(id).thenAccept(total -> {
                    if (total != null && timers.containsKey(id)) {
                        playtimes.put(id, new PlaytimeSample(total, playtime.sessionTime(id)));
                    }
                }).exceptionally(failure -> {
                    logBackgroundFailure("playtime", id, failure);
                    return null;
                });
            } catch (RuntimeException e) {
                logBackgroundFailure("playtime", id, e);
            }
        }

        RankService ranksService = ctx.services().get(RankService.class).orElse(null);
        if (ranksService != null) {
            try {
                ranksService.currentRank(id)
                        .thenAccept(rank -> rank.ifPresent(value -> {
                            if (timers.containsKey(id)) {
                                ranks.put(id, value);
                            }
                        }))
                        .exceptionally(failure -> {
                            logBackgroundFailure("rank", id, failure);
                            return null;
                        });
            } catch (RuntimeException e) {
                logBackgroundFailure("rank", id, e);
            }
        }

        if (config.get().userMetaEnabled) {
            fetchUserMeta(id);
        }
    }

    /** Reloads the metadata cache of one player (after /usermeta changed a value). */
    void refreshUserMeta(UUID id) {
        if (config.get().userMetaEnabled && timers.containsKey(id)) {
            fetchUserMeta(id);
        }
    }

    private void fetchUserMeta(UUID id) {
        UserMetaStore store = metaStore;
        if (store == null) {
            return;
        }
        try {
            store.load(id).thenAccept(values -> {
                if (timers.containsKey(id)) {
                    userMeta.put(id, values);
                }
            }).exceptionally(failure -> {
                logBackgroundFailure("user metadata", id, failure);
                return null;
            });
        } catch (RuntimeException e) {
            logBackgroundFailure("user metadata", id, e);
        }
    }

    private void logBackgroundFailure(String what, UUID id, Throwable failure) {
        if (warned.add("background:" + what)) {
            ctx.logger().warn("Reading the {} of {} failed; placeholders keep the last known value",
                    what, id, failure);
        }
    }

    // ---- global counts ----

    private void refreshCounts() {
        try {
            VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
            int total = 0;
            int visible = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                total++;
                if (vanish == null || !vanish.isVanished(player.getUniqueId())) {
                    visible++;
                }
            }
            counts = new OnlineCounts(visible, total);
        } catch (RuntimeException e) {
            if (warned.add("counts")) {
                ctx.logger().warn("Could not refresh the visible/total online counts", e);
            }
        }
    }

    // ---- lifecycle ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!stopping && hook != null) {
            startTracking(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stopTracking(event.getPlayer().getUniqueId());
    }

    // ---- command ----

    private int status(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        boolean active = hook != null && Bukkit.getPluginManager().isPluginEnabled(PAPI_PLUGIN);
        ctx.messages().send(sender, active ? "placeholders.status-hooked" : "placeholders.status-missing");
        ctx.messages().send(sender, "placeholders.status-cached", Msg.number("count", snapshots.size()));
        ctx.messages().send(sender, "placeholders.status-list",
                Msg.unparsed("placeholders", String.join(", ", PlaceholderValues.KEYS) + ", "
                        + String.join(", ", ProfilePlaceholders.KEYS)
                        + ", stat_<name> (" + dev.yanianz.atrium.api.statistics.StatisticsCatalog.entries().size()
                        + "), user_meta_<key>"));
        return Cmds.OK;
    }

    // ---- config ----

    private static void validate(PlaceholdersConfig cfg) {
        if (cfg.refreshIntervalSeconds < 1 || cfg.refreshIntervalSeconds > 5) {
            throw new IllegalArgumentException("placeholders.refreshIntervalSeconds must be 1-5");
        }
        if (cfg.cacheGraceSeconds < 0 || cfg.cacheGraceSeconds > 60) {
            throw new IllegalArgumentException("placeholders.cacheGraceSeconds must be 0-60");
        }
        if (cfg.storageRefreshSeconds < 5 || cfg.storageRefreshSeconds > 300) {
            throw new IllegalArgumentException("placeholders.storageRefreshSeconds must be 5-300");
        }
        if (cfg.statisticsRefreshSeconds < 10 || cfg.statisticsRefreshSeconds > 600) {
            throw new IllegalArgumentException("placeholders.statisticsRefreshSeconds must be 10-600");
        }
        if (cfg.fallback == null) {
            cfg.fallback = "";
        }
        if (cfg.trueText == null) {
            cfg.trueText = "";
        }
        if (cfg.falseText == null) {
            cfg.falseText = "";
        }
        if (cfg.fallback.length() > 256 || cfg.trueText.length() > 256 || cfg.falseText.length() > 256) {
            throw new IllegalArgumentException("placeholders texts must be at most 256 characters");
        }
    }
}
