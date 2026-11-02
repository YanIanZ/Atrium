package dev.yanianz.atrium.module.display;

import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.playtime.PlaytimeService;
import dev.yanianz.atrium.api.ranks.RankService;
import dev.yanianz.atrium.api.service.ServiceRegistry;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.util.Durations;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * Built-in placeholders shared by the tablist, scoreboard and bossbar modules. Optional services (economy,
 * playtime, ranks, vanish) are looked up on every call, so a module that is enabled or disabled later is
 * picked up; a missing service renders the configured "missing" text instead of failing.
 *
 * <p>{@link #serverView()} is computed once per update (it only reads the online list and the thread-safe
 * vanish service); {@link #values} runs on the viewer's own thread (it reads the viewer's world, ping and
 * display name).
 */
public final class DisplayPlaceholders {
    /** World-independent numbers computed once per update on the global thread. */
    public record ServerView(int online, int max, List<Player> vanished) {
        public ServerView {
            vanished = List.copyOf(vanished);
        }
    }

    private final ServiceRegistry services;
    private final Logger logger;
    private final AsyncValueCache<String> balances;
    private final AsyncValueCache<Optional<String>> ranks;
    private final AsyncValueCache<Long> playtimes;
    private final AtomicBoolean tpsUnavailableLogged = new AtomicBoolean();
    private volatile String missing = "-";

    public DisplayPlaceholders(ServiceRegistry services, Logger logger, Duration refresh) {
        this(services, logger, refresh, System::nanoTime);
    }

    DisplayPlaceholders(ServiceRegistry services, Logger logger, Duration refresh, LongSupplier clock) {
        this.services = services;
        this.logger = logger;
        long nanos = Math.max(TimeUnit.SECONDS.toNanos(1), refresh.toNanos());
        this.balances = new AsyncValueCache<>("balance", logger, nanos, clock);
        this.ranks = new AsyncValueCache<>("rank", logger, nanos, clock);
        this.playtimes = new AsyncValueCache<>("playtime", logger, nanos, clock);
    }

    /** Text shown for a value whose service is not installed or that is still loading. */
    public void missingText(String text) {
        this.missing = text == null ? "" : text;
    }

    public String missingText() {
        return missing;
    }

    /** Online count, max players and the vanished players (usually none). Any thread. */
    public ServerView serverView() {
        var online = Bukkit.getOnlinePlayers();
        List<Player> vanished = new ArrayList<>();
        Optional<VanishService> vanish = services.get(VanishService.class);
        if (vanish.isPresent()) {
            for (Player player : online) {
                if (vanish.get().isVanished(player.getUniqueId())) {
                    vanished.add(player);
                }
            }
        }
        return new ServerView(online.size(), Bukkit.getMaxPlayers(), vanished);
    }

    /** Viewer's thread: every built-in value for this viewer. */
    public PlaceholderValues values(Player viewer, ServerView view) {
        UUID id = viewer.getUniqueId();
        return new PlaceholderValues(
                viewer.getName(),
                viewer.displayName(),
                Integer.toString(visibleOnline(viewer, view)),
                Integer.toString(view.max()),
                viewer.getWorld().getName(),
                Integer.toString(viewer.getPing()),
                tps(),
                balance(id),
                playtime(id),
                rank(id));
    }

    /** Online players minus vanished players this viewer may not see, so counts never reveal staff. */
    public int visibleOnline(Player viewer, ServerView view) {
        if (view.vanished().isEmpty()) {
            return view.online();
        }
        Optional<VanishService> vanish = services.get(VanishService.class);
        if (vanish.isEmpty()) {
            return view.online();
        }
        int hidden = 0;
        for (Player target : view.vanished()) {
            if (!vanish.get().canSee(viewer, target)) {
                hidden++;
            }
        }
        return Math.max(0, view.online() - hidden);
    }

    public String balance(UUID player) {
        Optional<EconomyService> economy = services.get(EconomyService.class);
        if (economy.isEmpty()) {
            return missing;
        }
        EconomyService eco = economy.get();
        BigDecimal cached = eco.cachedBalance(player);
        if (cached != null) {
            return eco.format(cached);
        }
        String loaded = balances.get(player, () -> eco.balance(player).thenApply(eco::format));
        return loaded == null ? missing : loaded;
    }

    public String rank(UUID player) {
        Optional<RankService> service = services.get(RankService.class);
        if (service.isEmpty()) {
            return missing;
        }
        Optional<String> rank = ranks.get(player, () -> service.get().currentRank(player));
        return rank == null || rank.isEmpty() ? missing : rank.get();
    }

    public String playtime(UUID player) {
        Optional<PlaytimeService> service = services.get(PlaytimeService.class);
        if (service.isEmpty()) {
            return missing;
        }
        Long seconds = playtimes.get(player, () -> service.get().totalPlaytime(player));
        return seconds == null ? missing : Durations.format(Duration.ofSeconds(Math.max(0, seconds)), 2);
    }

    /**
     * TPS of the calling thread's region on Folia, or of the server on Paper. Some servers do not offer it;
     * then the missing text is shown and this is logged once.
     */
    public String tps() {
        try {
            double[] tps = Bukkit.getTPS();
            if (tps == null || tps.length == 0) {
                return missing;
            }
            return formatTps(tps[0]);
        } catch (UnsupportedOperationException e) {
            if (tpsUnavailableLogged.compareAndSet(false, true)) {
                logger.info("This server does not report TPS here; <tps> shows '{}'", missing);
            }
            return missing;
        }
    }

    static String formatTps(double tps) {
        if (Double.isNaN(tps) || tps < 0) {
            return "0.0";
        }
        return String.format(Locale.ROOT, "%.1f", Math.min(20.0, tps));
    }

    /** Drops cached async values of a player who left. */
    public void forget(UUID player) {
        balances.forget(player);
        ranks.forget(player);
        playtimes.forget(player);
    }

    public void clear() {
        balances.clear();
        ranks.clear();
        playtimes.clear();
    }
}
