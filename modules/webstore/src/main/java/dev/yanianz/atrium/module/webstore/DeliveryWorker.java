package dev.yanianz.atrium.module.webstore;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Polls pending deliveries and runs them. A poll runs on an I/O (virtual) thread; console commands are
 * dispatched on the global region thread; player messages are sent on each player's own thread.
 * Polls never overlap: a tick that finds the previous poll still running is skipped.
 */
final class DeliveryWorker {
    static final int BATCH_SIZE = 20;
    private static final long DISPATCH_TIMEOUT_SECONDS = 30;

    private final ModuleContext ctx;
    private final ConfigHandle<WebstoreConfig> config;
    private final DeliveryStore store;
    private final String claimer;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopped;

    DeliveryWorker(ModuleContext ctx, ConfigHandle<WebstoreConfig> config, DeliveryStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.claimer = String.format(Locale.ROOT, "%s:%d#%s", Bukkit.getIp().isBlank() ? "server" : Bukkit.getIp(),
                Bukkit.getPort(), UUID.randomUUID().toString().substring(0, 8));
    }

    /** Timer entry point: hands the blocking work to the I/O executor unless a poll is still running. */
    void tick() {
        if (stopped || !running.compareAndSet(false, true)) {
            return;
        }
        ctx.scheduler().supplyIo(() -> {
            try {
                poll();
            } catch (Exception e) {
                ctx.logger().warn("Webstore poll failed", e);
            } finally {
                running.set(false);
            }
            return null;
        });
    }

    void stop() {
        stopped = true;
    }

    private void poll() {
        WebstoreConfig settings = config.get();
        int maxAttempts = settings.attempts();
        int recovered = store.recoverStale(System.currentTimeMillis() - settings.staleClaimMillis(), maxAttempts).join();
        if (recovered > 0) {
            ctx.logger().warn("Webstore: {} stale or exhausted deliveries were requeued or failed", recovered);
        }
        List<DeliveryStore.Delivery> rows = store.pending(BATCH_SIZE, maxAttempts).join();
        for (DeliveryStore.Delivery row : rows) {
            if (stopped) {
                return;
            }
            process(row, settings, maxAttempts);
        }
    }

    private void process(DeliveryStore.Delivery row, WebstoreConfig settings, int maxAttempts) {
        boolean online = isOnline(row.recipientUuid());
        DeliveryPlan plan = DeliveryPlan.of(row, settings.packages, settings.requireOnline, online);
        switch (plan) {
            case DeliveryPlan.Skip skip -> {
                // Recipient offline and require-online is set: stays pending, no attempt used.
            }
            case DeliveryPlan.Fail fail -> {
                if (store.failPending(row.id(), fail.reason()).join()) {
                    ctx.logger().warn("Webstore order {} (package {}) failed: {}", row.orderId(), row.packageId(), fail.reason());
                }
            }
            case DeliveryPlan.Run run -> deliver(row, run, maxAttempts);
        }
    }

    private void deliver(DeliveryStore.Delivery row, DeliveryPlan.Run run, int maxAttempts) {
        if (!store.claim(row.id(), maxAttempts, claimer, System.currentTimeMillis()).join()) {
            return; // another server took it, or it changed meanwhile
        }
        try {
            ctx.scheduler().supplyGlobal(() -> {
                CommandSender console = Bukkit.getConsoleSender();
                for (String command : run.commands()) {
                    if (!Bukkit.dispatchCommand(console, command)) {
                        throw new IllegalStateException("command failed: " + command);
                    }
                }
                return null;
            }).get(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
            String status = store.failAttempt(row.id(), maxAttempts, reason).join();
            ctx.logger().warn("Webstore order {} (package {}) attempt {}/{} failed ({}): {}", row.orderId(),
                    row.packageId(), row.attempts() + 1, maxAttempts, status == null ? "state changed" : status, reason);
            return;
        }
        if (!store.markDelivered(row.id()).join()) {
            ctx.logger().warn("Webstore order {} ran its commands but its row was changed meanwhile; check its status",
                    row.orderId());
        } else {
            ctx.logger().info("Webstore order {} delivered: package {} to {} ({})", row.orderId(), row.packageId(),
                    row.recipientName(), run.recipient());
        }
        announce(row, run);
    }

    private static boolean isOnline(String rawUuid) {
        try {
            return Bukkit.getPlayer(UUID.fromString(rawUuid)) != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void announce(DeliveryStore.Delivery row, DeliveryPlan.Run run) {
        Component packageName = packageName(row, run.storePackage());
        String buyer = row.buyerName() == null || row.buyerName().isBlank() ? null : row.buyerName().strip();
        String recipientName = row.recipientName();

        Player recipient = Bukkit.getPlayer(run.recipient());
        if (recipient != null) {
            ctx.scheduler().entity(recipient, () -> notifyRecipient(recipient, row, packageName, buyer), null);
        }
        if (buyer != null && !buyer.equalsIgnoreCase(recipientName)) {
            Player buyerPlayer = Bukkit.getPlayerExact(buyer);
            if (buyerPlayer != null && !buyerPlayer.getUniqueId().equals(run.recipient())) {
                ctx.scheduler().entity(buyerPlayer, () -> ctx.messages().send(buyerPlayer, "webstore.buyer-delivered",
                        Msg.component("package", packageName), Msg.unparsed("player", recipientName)), null);
            }
        }
        if (config.get().broadcast) {
            String key = row.gift() ? "webstore.broadcast-gift" : "webstore.broadcast";
            for (Player online : Bukkit.getOnlinePlayers()) {
                ctx.scheduler().entity(online, () -> ctx.messages().send(online, key,
                        Msg.component("package", packageName), Msg.unparsed("player", recipientName),
                        Msg.unparsed("buyer", buyer == null ? "?" : buyer)), null);
            }
        }
    }

    private void notifyRecipient(Player player, DeliveryStore.Delivery row, Component packageName, String buyer) {
        var messages = ctx.messages();
        var pkg = Msg.component("package", packageName);
        if (row.gift()) {
            var from = buyer == null ? Msg.component("buyer", messages.render(player, "webstore.anonymous"))
                    : Msg.unparsed("buyer", buyer);
            messages.send(player, "webstore.gifted", from, pkg);
            String note = row.giftMessage() == null ? "" : row.giftMessage().replaceAll("\\p{Cntrl}", " ").strip();
            if (!note.isEmpty()) {
                // Written by the buyer on the website: always plain text, never MiniMessage.
                messages.send(player, "webstore.gift-message", Msg.unparsed("message", note), from);
            }
            showTitle(player, "webstore.gifted-title", "webstore.gifted-subtitle", from, pkg);
        } else {
            messages.send(player, "webstore.delivered", pkg);
            showTitle(player, "webstore.delivered-title", "webstore.delivered-subtitle", pkg);
        }
    }

    private void showTitle(Player player, String titleKey, String subtitleKey,
            TagResolver... tags) {
        Component title = ctx.messages().render(player, titleKey, tags);
        Component subtitle = ctx.messages().render(player, subtitleKey, tags);
        if (title == Component.empty() && subtitle == Component.empty()) {
            return;
        }
        player.showTitle(Title.title(title, subtitle));
    }

    private Component packageName(DeliveryStore.Delivery row, WebstoreConfig.StorePackage storePackage) {
        String display = storePackage.displayName;
        if (display == null || display.isBlank()) {
            return Component.text(row.packageId());
        }
        try {
            return ctx.messages().parseTrusted(display);
        } catch (RuntimeException e) {
            ctx.logger().warn("Invalid display-name for webstore package {}: {}", row.packageId(), e.getMessage());
            return Component.text(row.packageId());
        }
    }
}
