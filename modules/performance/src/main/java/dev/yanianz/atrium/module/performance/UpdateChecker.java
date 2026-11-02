package dev.yanianz.atrium.module.performance;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Asks the public GitHub releases API of the configured repository whether a newer version exists. The call
 * runs on the I/O executor, is rate limited to one check per interval and is opt-out. Nothing is downloaded or
 * started: the answer is a chat line with a link.
 */
final class UpdateChecker implements Listener {
    static final String PERM_NOTIFY = "atrium.updates.notify";

    /** What the last check found. */
    record Status(boolean checked, boolean newer, String latest, String url, String error) {
        static final Status NEVER = new Status(false, false, "", "", "");

        static Status failed(String error) {
            return new Status(true, false, "", "", error);
        }
    }

    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]{1,64})\"");
    private static final Pattern URL = Pattern.compile("\"html_url\"\\s*:\\s*\"([^\"]{1,512})\"");
    private static final Pattern DRAFT = Pattern.compile("\"draft\"\\s*:\\s*true");

    private final ModuleContext ctx;
    private final Supplier<PerformanceConfig> config;
    private final String currentVersion;
    private final HttpClient http;
    private final AtomicBoolean running = new AtomicBoolean();
    private final Set<UUID> notified = ConcurrentHashMap.newKeySet();
    private volatile Status status = Status.NEVER;
    private TaskHandle timer;

    UpdateChecker(ModuleContext ctx, Supplier<PerformanceConfig> config) {
        this.ctx = ctx;
        this.config = config;
        this.currentVersion = ctx.plugin().getPluginMeta().getVersion();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(
                HttpClient.Redirect.NORMAL).build();
    }

    Status status() {
        return status;
    }

    String currentVersion() {
        return currentVersion;
    }

    void start() {
        PerformanceConfig.Updates cfg = config.get().updates;
        if (!cfg.enabled) {
            return;
        }
        Duration interval = Duration.ofHours(cfg.intervalHours());
        if (cfg.checkOnStart) {
            ctx.track(ctx.scheduler().asyncLater(this::check, Duration.ofSeconds(5)));
        }
        timer = ctx.track(ctx.scheduler().asyncTimer(this::check, interval, interval));
    }

    void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
    }

    /** Runs the HTTP call on the I/O executor and publishes the result. Safe to call from any thread. */
    CompletableFuture<Status> check() {
        PerformanceConfig.Updates cfg = config.get().updates;
        if (!cfg.enabled) {
            return CompletableFuture.completedFuture(status);
        }
        if (!running.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(status);
        }
        return ctx.scheduler().supplyIo(() -> fetch(cfg)).whenComplete((result, error) -> {
            running.set(false);
            Status next = error != null ? Status.failed(deepest(error)) : result;
            if (error != null) {
                ctx.logger().warn("Update check failed: {}", next.error());
            }
            status = next;
            if (next.checked() && next.newer()) {
                ctx.logger().info("A newer Atrium release is available: {} (running {}) - {}",
                        next.latest(), currentVersion, next.url());
            }
        });
    }

    private Status fetch(PerformanceConfig.Updates cfg) throws Exception {
        String repository = cfg.repository == null ? "" : cfg.repository.strip();
        if (!repository.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")) {
            return Status.failed("invalid repository '" + repository + "'");
        }
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"))
                .timeout(Duration.ofSeconds(cfg.timeoutSeconds()))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Atrium-" + currentVersion)
                .GET()
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Status.failed("no published release");
        }
        if (response.statusCode() != 200) {
            return Status.failed("HTTP " + response.statusCode());
        }
        return parse(response.body(), currentVersion);
    }

    /** Parses the releases response. Pure so the shape is covered by a unit test. */
    static Status parse(String body, String currentVersion) {
        Matcher tag = TAG.matcher(body);
        if (!tag.find()) {
            return Status.failed("the release answer has no tag");
        }
        if (DRAFT.matcher(body).find()) {
            return new Status(true, false, tag.group(1), "", "");
        }
        String latest = tag.group(1);
        String url = "";
        Matcher link = URL.matcher(body);
        if (link.find()) {
            url = link.group(1);
        }
        boolean newer;
        try {
            newer = Version.parse(latest).newerThan(Version.parse(currentVersion));
        } catch (IllegalArgumentException e) {
            return Status.failed("cannot compare " + latest + " with " + currentVersion);
        }
        return new Status(true, newer, latest, url, "");
    }

    private static String deepest(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** Tells permitted players once per session about a newer release; never blocks the join. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PerformanceConfig.Updates cfg = config.get().updates;
        if (!cfg.enabled || !cfg.notifyOnJoin) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(PERM_NOTIFY) || !notified.add(player.getUniqueId())) {
            return;
        }
        Status current = status;
        if (!current.checked() || !current.newer()) {
            return;
        }
        ctx.track(ctx.scheduler().entityLater(player, () -> {
            if (player.isOnline()) {
                // Renamed key (was update-available): existing lang files kept the old, broken click line.
                ctx.messages().send(player, "performance.update-notice",
                        Msg.unparsed("latest", current.latest()),
                        Msg.unparsed("current", currentVersion),
                        Msg.unparsed("url", current.url().isEmpty() ? "-" : current.url()),
                        Msg.link("release_link", current.url()));
            }
        }, null, 40));
    }

    /** Nothing to undo besides the timer; the notification set is only a per-session guard. */
    void resetNotifications() {
        notified.clear();
    }
}
