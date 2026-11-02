package dev.yanianz.atrium.module.discord;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Owns the per-webhook queues and turns relay events into payloads. All network work happens on the core I/O
 * executor; callers only build a short string and append it to a queue.
 */
final class DiscordRelay {
    enum TestResult { QUEUED, MISSING, INVALID }

    record ChannelStatus(DiscordChannel channel, @Nullable String mask, @Nullable String invalidReason,
            WebhookQueue.@Nullable Stats stats) {}

    private record Snapshot(Map<DiscordChannel, WebhookQueue> channels,
            Map<String, WebhookQueue> targets,
            Map<DiscordChannel, String> invalid) {}

    private final ModuleContext ctx;
    private final ConfigHandle<DiscordConfig> config;
    private final HttpClient http;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of());
    private volatile boolean closed;

    DiscordRelay(ModuleContext ctx, ConfigHandle<DiscordConfig> config) {
        this.ctx = ctx;
        this.config = config;
        int timeout = clamp(config.get().requestTimeoutSeconds, 1, 60);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(ctx.scheduler().io())
                .build();
    }

    void start() {
        rebuild();
    }

    /** Re-reads the config and replaces every queue. Messages still queued for a removed webhook are dropped. */
    synchronized void rebuild() {
        if (closed) {
            return;
        }
        DiscordConfig cfg = config.get();
        Map<DiscordChannel, WebhookQueue> channels = new EnumMap<>(DiscordChannel.class);
        Map<String, WebhookQueue> targets = new HashMap<>();
        Map<DiscordChannel, String> invalid = new EnumMap<>(DiscordChannel.class);
        int timeout = clamp(cfg.requestTimeoutSeconds, 1, 60);

        for (DiscordChannel channel : DiscordChannel.values()) {
            String raw = channel.webhook(cfg);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                WebhookTarget target = WebhookTarget.parse(raw);
                String key = target.uri().toASCIIString();
                WebhookQueue queue = targets.get(key);
                if (queue == null) {
                    queue = new WebhookQueue(target, http, ctx.logger(), cfg.queueCapacity, cfg.maxRetries,
                            cfg.maxRateLimitRetries, cfg.baseBackoffMillis, cfg.maxBackoffMillis,
                            cfg.maxRateLimitWaitMillis, Duration.ofSeconds(timeout), System::currentTimeMillis);
                    queue.start(ctx.scheduler().io());
                    targets.put(key, queue);
                }
                channels.put(channel, queue);
                ctx.logger().info("Discord {} relay enabled for {}", channel.key(), target.masked());
            } catch (WebhookTarget.InvalidWebhookException e) {
                invalid.put(channel, e.getMessage());
                ctx.logger().warn("Discord {} webhook ignored: {}", channel.key(), e.getMessage());
            } catch (RuntimeException e) {
                invalid.put(channel, "unexpected configuration error");
                ctx.logger().warn("Discord {} webhook ignored ({})", channel.key(), e.getClass().getName());
            }
        }

        Snapshot old = snapshot;
        snapshot = new Snapshot(Map.copyOf(channels), Map.copyOf(targets), Map.copyOf(invalid));
        for (WebhookQueue queue : old.targets().values()) {
            queue.close();
        }
        ctx.logger().info("Discord relay ready: {} channel(s), {} webhook(s)", channels.size(), targets.size());
    }

    /**
     * Builds the payload and appends it to the channel's queue. Returns {@code false} when the channel has no
     * usable webhook (no exception is thrown for an unconfigured relay).
     */
    boolean publish(DiscordChannel channel, String template, Map<String, String> fields, @Nullable String playerName,
            @Nullable UUID playerId) {
        WebhookQueue queue = snapshot.channels().get(channel);
        if (queue == null) {
            return false;
        }
        DiscordConfig cfg = config.get();
        String content = DiscordText.limitContent(DiscordText.render(template, fields, true));
        if (content.isBlank()) {
            return false;
        }
        queue.enqueue(new WebhookQueue.WebhookMessage(content, username(cfg, playerName, playerId),
                avatar(cfg, playerName, playerId)));
        return true;
    }

    TestResult test(DiscordChannel channel) {
        Snapshot current = snapshot;
        if (current.invalid().containsKey(channel)) {
            return TestResult.INVALID;
        }
        WebhookQueue queue = current.channels().get(channel);
        if (queue == null) {
            return TestResult.MISSING;
        }
        DiscordConfig cfg = config.get();
        String content = DiscordText.limitContent(cfg.testMessage);
        if (content.isBlank()) {
            content = "Test message from Atrium.";
        }
        queue.enqueue(new WebhookQueue.WebhookMessage(content, username(cfg, "Server", null), null));
        return TestResult.QUEUED;
    }

    List<ChannelStatus> status() {
        Snapshot current = snapshot;
        List<ChannelStatus> result = new ArrayList<>(DiscordChannel.values().length);
        for (DiscordChannel channel : DiscordChannel.values()) {
            WebhookQueue queue = current.channels().get(channel);
            if (queue != null) {
                result.add(new ChannelStatus(channel, queue.target().masked(), null, queue.stats()));
            } else {
                result.add(new ChannelStatus(channel, null, current.invalid().get(channel), null));
            }
        }
        return List.copyOf(result);
    }

    boolean anyConfigured() {
        Snapshot current = snapshot;
        return !current.channels().isEmpty() || !current.invalid().isEmpty();
    }

    @Nullable
    String mask(DiscordChannel channel) {
        WebhookQueue queue = snapshot.channels().get(channel);
        return queue == null ? null : queue.target().masked();
    }

    @Nullable
    String invalidReason(DiscordChannel channel) {
        return snapshot.invalid().get(channel);
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        Snapshot old = snapshot;
        snapshot = new Snapshot(Map.of(), Map.of(), Map.of());
        for (WebhookQueue queue : old.targets().values()) {
            queue.close();
        }
        try {
            // Cancel in-flight requests instead of waiting for them: module disable must not block the server.
            http.shutdownNow();
        } catch (RuntimeException e) {
            ctx.logger().warn("Discord HTTP client did not shut down cleanly ({})", e.getClass().getSimpleName());
        }
    }

    private String username(DiscordConfig cfg, @Nullable String playerName, @Nullable UUID playerId) {
        Map<String, String> fields = Map.of(
                "player", playerName == null ? "Server" : playerName,
                "uuid", playerId == null ? "" : playerId.toString());
        return DiscordText.limitUsername(DiscordText.render(cfg.username, fields, false));
    }

    private @Nullable String avatar(DiscordConfig cfg, @Nullable String playerName, @Nullable UUID playerId) {
        if (playerId == null) {
            return null;
        }
        Map<String, String> fields = Map.of(
                "player", playerName == null ? "Server" : playerName,
                "uuid", playerId.toString());
        String url = DiscordText.render(cfg.avatarUrl, fields, false).strip();
        if (url.isBlank()
                || !url.toLowerCase(Locale.ROOT).startsWith("https://")
                || url.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return url;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
