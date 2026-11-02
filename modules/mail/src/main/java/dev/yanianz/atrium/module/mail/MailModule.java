package dev.yanianz.atrium.module.mail;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.chat.IgnoreService;
import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.util.Durations;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Offline mail: players send messages to online or offline players, read a paginated inbox, delete single
 * messages or the whole mailbox, and staff broadcast or send expiring mail.
 *
 * <p>Bodies are always inserted with {@link Msg#unparsed(String, String)}: a mail is player text, never
 * MiniMessage. Mutes are honoured through the optional {@link MuteService}; shadow-muted senders get the
 * normal confirmation but nothing is stored or delivered. When the recipient ignores the sender through the
 * optional {@link IgnoreService}, the message is stored with a dropped flag, never shown and never counted
 * in the mailbox limit, and the sender still receives the normal confirmation so the ignore list is not
 * leaked. Ignored and muted mail is deliberately indistinguishable from delivered mail.
 */
public final class MailModule implements AtriumModule, Listener {
    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<MailConfig> configHandle;
    private MailStore store;
    private MailRateLimiter limiter;
    private MailRateLimiter blastLimiter;
    private final MailAllConfirmation confirmations = new MailAllConfirmation();
    private volatile DateTimeFormatter formatter;

    @Override
    public String id() {
        return "mail";
    }

    @Override
    public String description() {
        return "Offline mail: send, read, clear, broadcast and expiring messages";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.configHandle = ctx.config(MailConfig.class, MailConfig::new);
        ctx.migrate(MailStore.MIGRATIONS);
        this.store = new MailStore(ctx.database());
        this.limiter = new MailRateLimiter();
        this.blastLimiter = new MailRateLimiter();
        rebuildFormatter();
        ctx.listen(this);
        new MailCommands(this, ctx).register();
        MailStore importStore = store;
        ctx.service(dev.yanianz.atrium.api.mail.MailAdminService.class, importStore::importAll);

        ctx.permission("atrium.mail", PermissionDefault.TRUE, "Use the mail system");
        ctx.permission("atrium.mail.send", PermissionDefault.TRUE, "Send mail to players");
        ctx.permission("atrium.mail.read", PermissionDefault.TRUE, "Read your mailbox");
        ctx.permission("atrium.mail.clear", PermissionDefault.TRUE, "Delete your mail");
        ctx.permission("atrium.mail.sendall", PermissionDefault.OP, "Send mail to every known player");
        ctx.permission("atrium.mail.mailall", PermissionDefault.OP,
                "Send a confirmed /mailall blast to every player seen recently");
        ctx.permission("atrium.mail.sendtemp", PermissionDefault.OP, "Send expiring mail");
        ctx.permission("atrium.mail.bypass.cooldown", PermissionDefault.OP, "Bypass the mail send cooldown");

        int minutes = MailFormat.clamp(config().purgeIntervalMinutes, 1, 1440);
        ctx.track(ctx.scheduler().asyncTimer(this::purgeExpired,
                Duration.ofMinutes(minutes), Duration.ofMinutes(minutes)));
    }

    @Override
    public void reload() {
        rebuildFormatter();
        limiter.clear();
        blastLimiter.clear();
    }

    @Override
    public void disable() {
        limiter.clear();
        blastLimiter.clear();
        confirmations.clear();
    }

    private void rebuildFormatter() {
        MailConfig config = config();
        try {
            this.formatter = MailFormat.formatter(config.dateFormat, config.timeZone);
        } catch (IllegalArgumentException e) {
            ctx.logger().warn("Invalid mail date format '{}' or time zone '{}'; using yyyy-MM-dd HH:mm UTC",
                    config.dateFormat, config.timeZone, e);
            this.formatter = MailFormat.formatter("yyyy-MM-dd HH:mm", "UTC");
        }
    }

    private MailConfig config() {
        return configHandle.get();
    }

    private void purgeExpired() {
        store.purgeExpired(System.currentTimeMillis()).thenAccept(deleted -> {
            if (deleted > 0) {
                ctx.logger().debug("Removed {} expired mail messages", deleted);
            }
        }).exceptionally(error -> {
            ctx.logger().warn("Could not purge expired mail", error);
            return null;
        });
    }

    // ---- commands ----

    int help(CommandContext<CommandSourceStack> context) {
        ctx.messages().send(context.getSource().getSender(), "mail.help");
        return Cmds.OK;
    }

    /** Shows a short usage line instead of a silent failure for an incomplete command. */
    int usage(CommandContext<CommandSourceStack> context, String key) {
        ctx.messages().send(context.getSource().getSender(), key);
        return Cmds.OK;
    }

    int send(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender actor = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "player");
        String body = body(context.getSource(), StringArgumentType.getString(context, "message"));
        if (denyMuted(context.getSource(), actor) || !allowSend(actor)) {
            return Cmds.OK;
        }
        sendTo(actor, name, body, defaultExpiry());
        return Cmds.OK;
    }

    int sendTemp(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender actor = context.getSource().getSender();
        String name = StringArgumentType.getString(context, "player");
        String rawDuration = StringArgumentType.getString(context, "duration");
        String body = body(context.getSource(), StringArgumentType.getString(context, "message"));
        Duration duration = Durations.parse(rawDuration)
                .filter(value -> !value.isZero() && !value.isNegative())
                .orElseThrow(() -> cmds.fail(context.getSource(), "core.invalid-duration",
                        Msg.unparsed("input", rawDuration)));
        Duration maximum = Duration.ofDays(MailFormat.clamp(config().maxTempDays, 1, 3650));
        if (duration.compareTo(maximum) > 0) {
            duration = maximum;
        }
        if (denyMuted(context.getSource(), actor) || !allowSend(actor)) {
            return Cmds.OK;
        }
        sendTo(actor, name, body, duration);
        return Cmds.OK;
    }

    int sendAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender actor = context.getSource().getSender();
        String body = body(context.getSource(), StringArgumentType.getString(context, "message"));
        if (denyMuted(context.getSource(), actor) || !allowSend(actor)) {
            return Cmds.OK;
        }
        recipients().thenAccept(recipients -> {
            if (recipients.isEmpty()) {
                reply(actor, "mail.sendall-none");
                return;
            }
            String senderName = senderName(actor);
            UUID senderId = senderId(actor);
            long now = System.currentTimeMillis();
            long expires = MailFormat.expiresAt(now, defaultExpiry());
            List<MailStore.Draft> drafts = new ArrayList<>(recipients.size());
            Map<UUID, Boolean> dropped = new LinkedHashMap<>();
            for (PlayerRef recipient : recipients) {
                boolean hidden = ignores(recipient.uuid(), senderId);
                drafts.add(new MailStore.Draft(recipient.uuid(), senderId, senderName, body, now, expires, hidden));
                dropped.put(recipient.uuid(), hidden);
            }
            store.insertAll(drafts, mailboxLimit()).thenAccept(delivered -> {
                reply(actor, "mail.sendall-sent",
                        Msg.number("count", delivered.size()), Msg.number("total", recipients.size()));
                Map<UUID, PlayerRef> byId = new LinkedHashMap<>();
                for (PlayerRef recipient : recipients) {
                    byId.putIfAbsent(recipient.uuid(), recipient);
                }
                for (UUID id : delivered) {
                    PlayerRef recipient = byId.get(id);
                    if (recipient != null && !Boolean.TRUE.equals(dropped.get(id))) {
                        notifyOnline(recipient, senderName);
                    }
                }
            }).exceptionally(error -> {
                failed(actor, "send mail to everyone", error);
                return null;
            });
        }).exceptionally(error -> {
            failed(actor, "resolve mail recipients", error);
            return null;
        });
        return Cmds.OK;
    }

    /**
     * First step of {@code /mailall}: resolves every account seen within the configured window, fixes the exact
     * recipient list and asks for confirmation. Nothing is stored or delivered until {@code /mailall confirm}.
     */
    int mailAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender actor = context.getSource().getSender();
        String body = body(context.getSource(), StringArgumentType.getString(context, "message"));
        if (denyMuted(context.getSource(), actor) || !allowSend(actor) || !allowBlast(actor)) {
            return Cmds.OK;
        }
        int days = MailFormat.clamp(config().mailAllRecentDays, 1, 3650);
        int maximum = MailFormat.clamp(config().mailAllMaxRecipients, 1, 10_000);
        long now = System.currentTimeMillis();
        long since = now - Duration.ofDays(days).toMillis();
        store.recentRecipients(since, maximum).thenAccept(recipients -> {
            if (recipients.isEmpty()) {
                reply(actor, "mail.mailall-none", Msg.number("days", days));
                return;
            }
            int window = MailFormat.clamp(config().mailAllConfirmationSeconds, 10, 600);
            confirmations.arm(key(actor), body, recipients, now, window * 1000L);
            int capped = MailFormat.clamp(config().mailAllMaxRecipients, 1, 10_000);
            Component confirm = ctx.messages().render(actor, "mail.mailall-confirm-button")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/mailall confirm"));
            Component cancel = ctx.messages().render(actor, "mail.mailall-cancel-button")
                    .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/mailall cancel"));
            boolean truncated = recipients.size() >= capped;
            reply(actor, "mail.mailall-confirm",
                    Msg.number("count", recipients.size()),
                    Msg.number("days", days),
                    Msg.number("seconds", window),
                    Msg.unparsed("body", body),
                    Msg.component("confirm", confirm),
                    Msg.component("cancel", cancel),
                    Msg.component("truncated", truncated
                            ? ctx.messages().render(actor, "mail.mailall-truncated")
                            : Component.empty()));
        }).exceptionally(error -> {
            failed(actor, "resolve /mailall recipients", error);
            return null;
        });
        return Cmds.OK;
    }

    /** Second step: sends the blast that was fixed by the previous {@code /mailall <message>}. */
    int confirmMailAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSender actor = context.getSource().getSender();
        long now = System.currentTimeMillis();
        Optional<MailAllConfirmation.Pending> found = confirmations.take(key(actor), now);
        if (found.isEmpty()) {
            reply(actor, "mail.mailall-expired");
            return Cmds.OK;
        }
        if (denyMuted(context.getSource(), actor) || !allowSend(actor)) {
            return Cmds.OK;
        }
        MailAllConfirmation.Pending blast = found.get();
        String senderName = senderName(actor);
        UUID senderId = senderId(actor);
        long expires = MailFormat.expiresAt(now, defaultExpiry());
        List<MailStore.Draft> drafts = new ArrayList<>(blast.recipients().size());
        for (PlayerRef recipient : blast.recipients()) {
            boolean hidden = ignores(recipient.uuid(), senderId);
            drafts.add(new MailStore.Draft(recipient.uuid(), senderId, senderName, blast.message(), now, expires,
                    hidden));
        }
        store.insertAll(drafts, mailboxLimit()).thenAccept(delivered -> {
            reply(actor, "mail.mailall-sent", Msg.number("count", delivered.size()),
                    Msg.number("total", blast.recipients().size()));
            for (PlayerRef recipient : blast.recipients()) {
                if (delivered.contains(recipient.uuid())) {
                    notifyOnline(recipient, senderName);
                }
            }
        }).exceptionally(error -> {
            failed(actor, "send confirmed /mailall", error);
            return null;
        });
        return Cmds.OK;
    }

    int cancelMailAll(CommandContext<CommandSourceStack> context) {
        CommandSender actor = context.getSource().getSender();
        confirmations.cancel(key(actor));
        reply(actor, "mail.mailall-cancelled");
        return Cmds.OK;
    }

    /** Separate, longer cooldown for blasts so a confirmed mail cannot be repeated in a loop. */
    private boolean allowBlast(CommandSender actor) {
        if (!(actor instanceof Player player) || player.hasPermission("atrium.mail.bypass.cooldown")) {
            return true;
        }
        long cooldown = Math.max(0, config().mailAllCooldownSeconds) * 1000L;
        MailRateLimiter.Decision decision = blastLimiter.check(player.getUniqueId(), System.currentTimeMillis(),
                cooldown);
        if (decision.allowed()) {
            return true;
        }
        long seconds = Math.max(1, (decision.remainingMillis() + 999) / 1000);
        ctx.messages().send(player, "mail.mailall-rate-limited", Msg.number("seconds", seconds));
        return false;
    }

    private static String key(CommandSender sender) {
        return sender instanceof Player player ? "p:" + player.getUniqueId() : "console";
    }

    int read(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        int requested = Cmds.hasArgument(context, "page")
                ? IntegerArgumentType.getInteger(context, "page")
                : 1;
        int pageSize = MailFormat.clamp(config().pageSize, 1, 50);
        store.readPage(player.getUniqueId(), requested, pageSize).thenAccept(page ->
                ctx.scheduler().entity(player, () -> show(player, page), null)
        ).exceptionally(error -> {
            failed(player, "read mail", error);
            return null;
        });
        return Cmds.OK;
    }

    private void show(Player player, MailStore.Page page) {
        if (page.total() == 0) {
            ctx.messages().send(player, "mail.empty");
            return;
        }
        ctx.messages().send(player, "mail.list-header",
                Msg.number("page", page.page()),
                Msg.number("pages", page.pages()),
                Msg.number("total", page.total()),
                Msg.number("unread", page.unread()));
        for (MailStore.Entry entry : page.entries()) {
            Component expiry = entry.expires() == 0
                    ? ctx.messages().render(player, "mail.expiry-never")
                    : ctx.messages().render(player, "mail.expiry-at",
                            Msg.unparsed("date", MailFormat.date(formatter, entry.expires())));
            ctx.messages().send(player, entry.read() ? "mail.list-read" : "mail.list-unread",
                    Msg.number("id", entry.id()),
                    Msg.unparsed("sender", entry.senderName()),
                    Msg.unparsed("date", MailFormat.date(formatter, entry.created())),
                    Msg.component("expiry", expiry));
            ctx.messages().send(player, "mail.list-body", Msg.unparsed("body", entry.body()));
        }
    }

    int clearAll(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        store.clearAll(player.getUniqueId()).thenAccept(deleted ->
                ctx.scheduler().entity(player, () -> ctx.messages().send(player, "mail.cleared-all",
                        Msg.number("count", deleted)), null)
        ).exceptionally(error -> {
            failed(player, "clear mailbox", error);
            return null;
        });
        return Cmds.OK;
    }

    int clearOne(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Player player = cmds.player(context.getSource());
        long id = LongArgumentType.getLong(context, "id");
        store.clearOne(player.getUniqueId(), id).thenAccept(deleted -> ctx.scheduler().entity(player, () -> {
            ctx.messages().send(player, deleted ? "mail.cleared-one" : "mail.not-found", Msg.number("id", id));
        }, null)).exceptionally(error -> {
            failed(player, "delete mail " + id, error);
            return null;
        });
        return Cmds.OK;
    }

    // ---- delivery ----

    private void sendTo(CommandSender actor, String name, String body, Duration expiry) {
        ctx.users().resolveAccount(name, false).thenAccept(found -> {
            if (found.isEmpty()) {
                reply(actor, "core.user-not-found", Msg.unparsed("name", name));
                return;
            }
            PlayerRef recipient = found.get();
            String senderName = senderName(actor);
            UUID senderId = senderId(actor);
            long now = System.currentTimeMillis();
            boolean hidden = ignores(recipient.uuid(), senderId);
            MailStore.Draft draft = new MailStore.Draft(recipient.uuid(), senderId, senderName,
                    body, now, MailFormat.expiresAt(now, expiry), hidden);
            store.insertAll(List.of(draft), mailboxLimit()).thenAccept(delivered -> {
                if (delivered.isEmpty()) {
                    reply(actor, "mail.full", Msg.unparsed("target", recipient.name()));
                    return;
                }
                // Same reply whether the mail was delivered or dropped: the ignore list must not leak.
                reply(actor, "mail.sent", Msg.unparsed("target", recipient.name()));
                if (!hidden) {
                    notifyOnline(recipient, senderName);
                }
            }).exceptionally(error -> {
                failed(actor, "send mail", error);
                return null;
            });
        }).exceptionally(error -> {
            failed(actor, "resolve mail recipient", error);
            return null;
        });
    }

    private CompletableFuture<List<PlayerRef>> recipients() {
        if (!config().sendAllIncludesOffline) {
            List<PlayerRef> online = new ArrayList<>();
            for (AtriumUser user : ctx.users().online()) {
                online.add(new PlayerRef(user.uuid(), user.name()));
            }
            return CompletableFuture.completedFuture(List.copyOf(online));
        }
        int maximum = MailFormat.clamp(config().sendAllMaxRecipients, 1, 10_000);
        List<String> names = List.copyOf(ctx.users().knownNames("", maximum));
        List<CompletableFuture<Optional<PlayerRef>>> lookups = names.stream()
                .map(name -> ctx.users().resolveAccount(name, false))
                .toList();
        return CompletableFuture.allOf(lookups.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<UUID, PlayerRef> unique = new LinkedHashMap<>();
            for (CompletableFuture<Optional<PlayerRef>> lookup : lookups) {
                lookup.join().ifPresent(ref -> unique.putIfAbsent(ref.uuid(), ref));
            }
            return List.copyOf(unique.values());
        });
    }

    private void notifyOnline(PlayerRef recipient, String senderName) {
        Player online = ctx.plugin().getServer().getPlayer(recipient.uuid());
        if (online == null) {
            return;
        }
        ctx.scheduler().entity(online, () -> {
            if (online.hasPermission("atrium.mail")) {
                ctx.messages().send(online, "mail.notify-online", Msg.unparsed("sender", senderName));
            }
        }, null);
    }

    // ---- checks and helpers ----

    /**
     * Whether the recipient ignores this sender, in which case the mail is stored as dropped. Console mail
     * and self-mail are never dropped. A failing service is logged and treated as "not ignored" so mail is
     * never lost silently.
     */
    private boolean ignores(UUID recipient, UUID sender) {
        if (sender == null || recipient == null || recipient.equals(sender)) {
            return false;
        }
        Optional<IgnoreService> service = ctx.services().get(IgnoreService.class);
        if (service.isEmpty()) {
            return false;
        }
        try {
            return service.get().isIgnoring(recipient, sender);
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not read the ignore list of {} while delivering mail", recipient, e);
            return false;
        }
    }

    /**
     * {@code true} when sending must stop. A normal mute fails with a message; a shadow mute gets a neutral
     * confirmation without storing or delivering anything.
     */
    private boolean denyMuted(CommandSourceStack source, CommandSender actor) throws CommandSyntaxException {
        if (!(actor instanceof Player player)) {
            return false;
        }
        Optional<MuteService> service = ctx.services().get(MuteService.class);
        if (service.isEmpty()) {
            return false;
        }
        try {
            UUID id = player.getUniqueId();
            if (service.get().isMuted(id)) {
                throw cmds.fail(source, "mail.muted");
            }
            if (service.get().isShadowMuted(id)) {
                ctx.messages().send(player, "mail.accepted");
                return true;
            }
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not check the mute state of {}", player.getName(), e);
        }
        return false;
    }

    private boolean allowSend(CommandSender actor) {
        if (!(actor instanceof Player player) || player.hasPermission("atrium.mail.bypass.cooldown")) {
            return true;
        }
        long cooldown = Math.max(0, config().sendCooldownSeconds) * 1000L;
        MailRateLimiter.Decision decision = limiter.check(player.getUniqueId(), System.currentTimeMillis(), cooldown);
        if (decision.allowed()) {
            return true;
        }
        long seconds = Math.max(1, (decision.remainingMillis() + 999) / 1000);
        ctx.messages().send(player, "mail.rate-limited", Msg.number("seconds", seconds));
        return false;
    }

    private String body(CommandSourceStack source, String raw) throws CommandSyntaxException {
        String text = MailFormat.clean(raw);
        if (MailFormat.blank(text)) {
            throw cmds.fail(source, "mail.blank");
        }
        int maximum = MailFormat.clamp(config().maxBodyLength, 1, 8192);
        if (MailFormat.tooLong(text, maximum)) {
            throw cmds.fail(source, "mail.too-long", Msg.number("max", maximum));
        }
        return text;
    }

    private Duration defaultExpiry() {
        int days = config().defaultExpiryDays;
        return days <= 0 ? Duration.ZERO : Duration.ofDays(Math.min(days, 36_500));
    }

    private int mailboxLimit() {
        return MailFormat.clamp(config().mailboxLimit, 1, 9999);
    }

    private static UUID senderId(CommandSender actor) {
        return actor instanceof Player player ? player.getUniqueId() : null;
    }

    private String senderName(CommandSender actor) {
        return actor instanceof Player player ? player.getName() : config().consoleSenderName;
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }

    private void failed(CommandSender sender, String action, Throwable error) {
        ctx.logger().warn("Mail {} failed", action, error);
        reply(sender, "core.error");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("atrium.mail")) {
            return;
        }
        store.unread(player.getUniqueId(), System.currentTimeMillis()).thenAccept(count -> {
            if (count > 0) {
                ctx.scheduler().entity(player, () -> ctx.messages().send(player, "mail.notify-join",
                        Msg.number("count", count)), null);
            }
        }).exceptionally(error -> {
            ctx.logger().warn("Could not check the mailbox of {}", player.getName(), error);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        limiter.forget(event.getPlayer().getUniqueId());
        blastLimiter.forget(event.getPlayer().getUniqueId());
        confirmations.forget(key(event.getPlayer()));
    }
}
