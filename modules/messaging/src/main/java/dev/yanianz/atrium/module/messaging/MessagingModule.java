package dev.yanianz.atrium.module.messaging;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.proxy.ProxyMessage;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.user.PlayerRef;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * Admin broadcast helpers ({@code /actionbarmsg}, {@code /ctellraw}), player-to-staff help
 * ({@code /helpop}) and persisted staff alerts ({@code /alert}).
 *
 * <p>Admin messages are trusted MiniMessage templates; the {@code <player>} placeholder is always inserted
 * as unparsed text. Helpop text, alert reasons and everything received through the proxy are untrusted and
 * are inserted with {@link Msg#unparsed(String, String)} only. Action bars are re-sent on each target's own
 * region thread and stop as soon as their duration ends, so an idle server schedules no per-tick work.
 */
public final class MessagingModule implements AtriumModule, Listener {
    static final String PERM_ACTIONBAR = "atrium.messaging.actionbarmsg";
    static final String PERM_CTELLRAW = "atrium.messaging.ctellraw";
    static final String PERM_SILENT = "atrium.messaging.silent";
    static final String PERM_HELPOP = "atrium.helpop";
    static final String PERM_HELPOP_RECEIVE = "atrium.helpop.receive";
    static final String PERM_HELPOP_BYPASS = "atrium.helpop.bypass.cooldown";
    static final String PERM_ALERT = "atrium.alert";
    static final String PERM_ALERT_RECEIVE = "atrium.alert.receive";
    static final String PERM_COLORPICKER = "atrium.colorpicker";
    static final String PERM_COLORLIMITS = "atrium.colorlimits";
    static final String PERM_TP = "atrium.tp";

    static final String HELPOP_TOPIC = "helpop";
    private static final String HELPOP_COOLDOWN = "messaging:helpop";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<MessagingConfig> configHandle;
    private AlertStore alerts;
    private ProxyService.Subscription helpOpSubscription;
    private volatile DateTimeFormatter dates;

    @Override
    public String id() {
        return "messaging";
    }

    @Override
    public String description() {
        return "Action bar messages, raw tellraw, helpop and staff alerts";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("proxy", "vanish", "moderation");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.configHandle = ctx.config(MessagingConfig.class, MessagingConfig::new);
        ctx.migrate(AlertStore.MIGRATIONS);
        this.alerts = new AlertStore(ctx.database());
        rebuildFormatter();
        ctx.listen(this);
        new MessagingCommands(this, ctx).register();

        ctx.permission(PERM_ACTIONBAR, PermissionDefault.OP, "Send action bar messages to players");
        ctx.permission(PERM_CTELLRAW, PermissionDefault.OP, "Send raw MiniMessage to players");
        ctx.permission(PERM_SILENT, PermissionDefault.OP, "Use -s to hide command feedback");
        ctx.permission(PERM_HELPOP, PermissionDefault.TRUE, "Ask staff for help");
        ctx.permission(PERM_HELPOP_RECEIVE, PermissionDefault.OP, "Receive helpop requests");
        ctx.permission(PERM_HELPOP_BYPASS, PermissionDefault.OP, "Bypass the helpop cooldown");
        ctx.permission(PERM_ALERT, PermissionDefault.OP, "Manage player alerts");
        ctx.permission(PERM_ALERT_RECEIVE, PermissionDefault.OP, "See player alerts when they join");
        ctx.permission(PERM_COLORPICKER, PermissionDefault.TRUE, "Show the clickable colour palette");
        ctx.permission(PERM_COLORLIMITS, PermissionDefault.TRUE, "Show your colour and format permissions");

        subscribeHelpOp();
        int minutes = config().clampAlertPurgeMinutes();
        ctx.track(ctx.scheduler().asyncTimer(this::purgeAlerts,
                Duration.ofMinutes(minutes), Duration.ofMinutes(minutes)));
    }

    @Override
    public void reload() {
        rebuildFormatter();
    }

    @Override
    public void disable() {
        if (helpOpSubscription != null) {
            helpOpSubscription.close();
            helpOpSubscription = null;
        }
    }

    MessagingConfig config() {
        return configHandle.get();
    }

    private void rebuildFormatter() {
        MessagingConfig config = config();
        try {
            ZoneId zone = config.timeZone == null || config.timeZone.isBlank()
                    ? ZoneId.systemDefault() : ZoneId.of(config.timeZone.strip());
            this.dates = DateTimeFormatter.ofPattern(config.dateFormat, java.util.Locale.ROOT).withZone(zone);
        } catch (RuntimeException e) {
            ctx.logger().warn("Invalid messaging date format '{}' or time zone '{}'; using yyyy-MM-dd HH:mm UTC",
                    config.dateFormat, config.timeZone, e);
            this.dates = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT)
                    .withZone(ZoneId.of("UTC"));
        }
    }

    private void purgeAlerts() {
        alerts.purgeExpired(System.currentTimeMillis()).thenAccept(deleted -> {
            if (deleted > 0) {
                ctx.logger().debug("Removed {} expired alerts", deleted);
            }
        }).exceptionally(error -> {
            ctx.logger().warn("Could not purge expired alerts", error);
            return null;
        });
    }

    // ---- command entry points ----

    int helpBar(CommandContext<CommandSourceStack> c) {
        return help(c, "messaging.help-actionbar");
    }

    int helpTellraw(CommandContext<CommandSourceStack> c) {
        return help(c, "messaging.help-tellraw");
    }

    int helpHelpOp(CommandContext<CommandSourceStack> c) {
        return help(c, "messaging.help-helpop");
    }

    int helpAlert(CommandContext<CommandSourceStack> c) {
        return help(c, "messaging.help-alert");
    }

    private int help(CommandContext<CommandSourceStack> c, String key) {
        ctx.messages().send(c.getSource().getSender(), key,
                Msg.number("max", config().clampActionBarMax()));
        return Cmds.OK;
    }

    int actionBarAll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return actionBar(c, true);
    }

    int actionBarTarget(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return actionBar(c, false);
    }

    private int actionBar(CommandContext<CommandSourceStack> c, boolean all) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        MessagingConfig config = config();
        int maximum = config.clampActionBarMax();
        int defaultSeconds = Math.min(config.clampActionBarDefault(), maximum);
        MessageFlags.Result flags = flags(source, StringArgumentType.getString(c, "message"),
                defaultSeconds, maximum, true);
        String template = adminText(source, flags.text());
        validateTemplate(source, template);

        List<Player> targets = targets(source, c, all);
        for (Player target : targets) {
            scheduleActionBar(target, template, flags.seconds());
        }
        if (!flags.silent()) {
            if (all) {
                reply(sender, "messaging.actionbar-sent-all",
                        Msg.number("count", targets.size()), Msg.number("seconds", flags.seconds()));
            } else {
                reply(sender, "messaging.actionbar-sent",
                        Msg.unparsed("player", targets.getFirst().getName()),
                        Msg.number("seconds", flags.seconds()));
            }
        }
        return Cmds.OK;
    }

    int tellrawAll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return tellraw(c, true);
    }

    int tellrawTarget(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return tellraw(c, false);
    }

    private int tellraw(CommandContext<CommandSourceStack> c, boolean all) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        MessageFlags.Result flags = flags(source, StringArgumentType.getString(c, "message"),
                1, 1, false);
        String template = adminText(source, flags.text());
        validateTemplate(source, template);

        List<Player> targets = targets(source, c, all);
        for (Player target : targets) {
            ctx.scheduler().entity(target, () -> {
                if (target.isOnline()) {
                    target.sendMessage(render(template, target));
                }
            }, null);
        }
        if (!flags.silent()) {
            if (all) {
                reply(sender, "messaging.tellraw-sent-all", Msg.number("count", targets.size()));
            } else {
                reply(sender, "messaging.tellraw-sent",
                        Msg.unparsed("player", targets.getFirst().getName()));
            }
        }
        return Cmds.OK;
    }

    int helpOp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        String text = untrustedText(StringArgumentType.getString(c, "message"), config().clampHelpOpLength());
        if (text.isBlank()) {
            throw cmds.fail(source, "messaging.blank");
        }

        boolean shadow = false;
        if (sender instanceof Player player) {
            Optional<MuteService> mutes = ctx.services().get(MuteService.class);
            boolean muted = false;
            if (mutes.isPresent()) {
                try {
                    muted = mutes.get().isMuted(player.getUniqueId());
                    shadow = mutes.get().isShadowMuted(player.getUniqueId());
                } catch (RuntimeException e) {
                    ctx.logger().warn("Could not check the mute state of {} for /helpop", player.getName(), e);
                }
            }
            Duration cooldown = Duration.ZERO;
            int cooldownSeconds = config().clampHelpOpCooldown();
            if (!muted && !shadow && cooldownSeconds > 0 && !player.hasPermission(PERM_HELPOP_BYPASS)) {
                cooldown = ctx.cooldowns().tryAcquire(ctx.users().user(player), HELPOP_COOLDOWN,
                        Duration.ofSeconds(cooldownSeconds));
            }
            HelpOpGate.Decision decision = HelpOpGate.decide(muted, shadow, cooldown);
            switch (decision) {
                case MUTED -> throw cmds.fail(source, "messaging.helpop-muted");
                case SHADOW_MUTED -> {
                    // Same neutral confirmation as a delivered request: a shadow mute must stay invisible.
                    ctx.messages().send(player, "messaging.helpop-accepted");
                    return Cmds.OK;
                }
                case COOLDOWN -> throw cmds.fail(source, "core.cooldown", Msg.duration("time", cooldown));
                case ALLOW -> {
                }
            }
        }

        String name = senderName(sender);
        List<Player> staff = helpOpRecipients(sender);
        for (Player recipient : staff) {
            sendHelpOp(recipient, sender, name, text);
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "messaging.helpop-console",
                Msg.unparsed("player", name), Msg.unparsed("message", text));

        boolean published = publishHelpOp(name, text);
        long visible = staff.stream().filter(player -> canSee(sender, player)).count();
        if (staff.isEmpty() && !published) {
            ctx.messages().send(sender, "messaging.helpop-no-staff");
        } else if (published) {
            ctx.messages().send(sender, "messaging.helpop-sent-network", Msg.number("count", visible));
        } else if (visible == 0) {
            // Only vanished staff received it: confirm delivery without revealing them.
            ctx.messages().send(sender, "messaging.helpop-sent-quiet");
        } else {
            ctx.messages().send(sender, "messaging.helpop-sent", Msg.number("count", visible));
        }
        return Cmds.OK;
    }

    int alertAdd(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String reason = untrustedText(StringArgumentType.getString(c, "reason"),
                config().clampAlertReasonLength());
        if (reason.isBlank()) {
            throw cmds.fail(c.getSource(), "messaging.blank");
        }
        long now = System.currentTimeMillis();
        int days = config().alertExpiryDaysClamped();
        long expires = days == 0 ? 0 : now + days * 86_400_000L;
        String by = senderName(sender);
        ctx.users().resolveAccount(name, true).thenAccept(found -> {
            if (found.isEmpty()) {
                reply(sender, "core.user-not-found", Msg.unparsed("name", name));
                return;
            }
            PlayerRef ref = found.get();
            alerts.add(new AlertStore.Alert(0, ref.uuid(), ref.name(), reason, now, by, expires))
                    .thenAccept(id -> reply(sender, "messaging.alert-added",
                            Msg.number("id", id), Msg.unparsed("player", ref.name())))
                    .exceptionally(error -> {
                        failed(sender, "store an alert", error);
                        return null;
                    });
        }).exceptionally(error -> {
            failed(sender, "resolve the alert target", error);
            return null;
        });
        return Cmds.OK;
    }

    int alertRemove(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        ctx.users().resolveAccount(name, true).thenAccept(found -> {
            if (found.isEmpty()) {
                reply(sender, "core.user-not-found", Msg.unparsed("name", name));
                return;
            }
            PlayerRef ref = found.get();
            alerts.removeAll(ref.uuid()).thenAccept(deleted -> {
                if (deleted == 0) {
                    reply(sender, "messaging.alert-none", Msg.unparsed("player", ref.name()));
                } else {
                    reply(sender, "messaging.alert-removed",
                            Msg.number("count", deleted), Msg.unparsed("player", ref.name()));
                }
            }).exceptionally(error -> {
                failed(sender, "remove alerts", error);
                return null;
            });
        }).exceptionally(error -> {
            failed(sender, "resolve the alert target", error);
            return null;
        });
        return Cmds.OK;
    }

    int alertList(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        int requested = Cmds.hasArgument(c, "page") ? IntegerArgumentType.getInteger(c, "page") : 1;
        int size = config().clampAlertPageSize();
        long now = System.currentTimeMillis();
        alerts.page(requested, size, now).thenAccept(page -> {
            if (sender instanceof Player player) {
                ctx.scheduler().entity(player, () -> showAlerts(player, page), null);
            } else {
                showAlerts(sender, page);
            }
        }).exceptionally(error -> {
            failed(sender, "list alerts", error);
            return null;
        });
        return Cmds.OK;
    }

    private void showAlerts(CommandSender sender, AlertStore.Page page) {
        if (page.total() == 0) {
            ctx.messages().send(sender, "messaging.alert-list-empty");
            return;
        }
        ctx.messages().send(sender, "messaging.alert-list-header",
                Msg.number("page", page.page()), Msg.number("pages", page.pages()),
                Msg.number("total", page.total()));
        for (AlertStore.Alert alert : page.entries()) {
            Component expiry = alert.expires() == 0
                    ? ctx.messages().render(sender, "messaging.alert-expiry-never")
                    : ctx.messages().render(sender, "messaging.alert-expiry",
                            Msg.unparsed("date", date(alert.expires())));
            ctx.messages().send(sender, "messaging.alert-list-entry",
                    Msg.number("id", alert.id()),
                    Msg.unparsed("player", alert.playerName()),
                    Msg.unparsed("reason", alert.reason()),
                    Msg.unparsed("by", alert.createdBy()),
                    Msg.unparsed("date", date(alert.created())),
                    Msg.component("expiry", expiry));
        }
    }

    // ---- colour tools ----

    int colorPicker(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ctx.messages().send(sender, "messaging.colorpicker-header");
        List<Component> named = new ArrayList<>();
        for (ColorPicker.Swatch swatch : ColorPicker.NAMED) {
            named.add(swatch(sender, swatch, swatch.label()));
        }
        ctx.messages().send(sender, "messaging.colorpicker-named",
                Msg.component("swatches", Component.join(JoinConfiguration.spaces(), named)));
        ctx.messages().send(sender, "messaging.colorpicker-hex-header");
        List<ColorPicker.Swatch> hex = ColorPicker.hexGrid();
        for (int start = 0; start < hex.size(); start += 12) {
            List<Component> row = new ArrayList<>();
            for (ColorPicker.Swatch swatch : hex.subList(start, Math.min(start + 12, hex.size()))) {
                row.add(swatch(sender, swatch, "██"));
            }
            ctx.messages().send(sender, "messaging.colorpicker-hex-row",
                    Msg.component("swatches", Component.join(JoinConfiguration.spaces(), row)));
        }
        return Cmds.OK;
    }

    private Component swatch(CommandSender sender, ColorPicker.Swatch swatch, String label) {
        return Component.text(label)
                .color(TextColor.color(swatch.rgb()))
                .clickEvent(ClickEvent.suggestCommand(swatch.tag()))
                .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "messaging.colorpicker-hover",
                        Msg.unparsed("value", swatch.tag()))));
    }

    int colorLimits(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ctx.messages().send(sender, "messaging.colorlimits-header");
        limitsRow(sender, "chat", "atrium.chat.color", "atrium.chat.format", null);
        limitsRow(sender, "nick", "atrium.nick.color", "atrium.nick.format", "atrium.nick.gradient");
        limitsRow(sender, "signs", "atrium.signs.color", "atrium.signs.format", null);
        limitsRow(sender, "anvils", "atrium.anvil.color", "atrium.anvil.format", null);
        return Cmds.OK;
    }

    /**
     * One /colorlimits row. A node that no enabled module registers is shown as unavailable instead of
     * pretending the player lacks it; the check is the live Bukkit permission registry.
     */
    private void limitsRow(CommandSender sender, String area, String colorNode, String formatNode,
            @Nullable String gradientNode) {
        ctx.messages().send(sender, "messaging.colorlimits-row",
                Msg.component("area", ctx.messages().render(sender, "messaging.colorlimits-area-" + area)),
                Msg.component("color", limitsStatus(sender, colorNode)),
                Msg.component("format", limitsStatus(sender, formatNode)));
        if (gradientNode != null) {
            ctx.messages().send(sender, "messaging.colorlimits-gradient",
                    Msg.component("gradient", limitsStatus(sender, gradientNode)));
        }
    }

    private Component limitsStatus(CommandSender sender, String node) {
        if (node == null || Bukkit.getPluginManager().getPermission(node) == null) {
            return ctx.messages().render(sender, "messaging.colorlimits-na");
        }
        return ctx.messages().render(sender, sender.hasPermission(node)
                ? "messaging.colorlimits-yes"
                : "messaging.colorlimits-no");
    }

    // ---- flags and text ----

    private MessageFlags.Result flags(CommandSourceStack source, String raw, int defaultSeconds,
            int maxSeconds, boolean durationAllowed) throws CommandSyntaxException {
        CommandSender sender = source.getSender();
        MessageFlags.Result parsed = MessageFlags.parse(raw, defaultSeconds, maxSeconds,
                sender.hasPermission(PERM_SILENT), durationAllowed);
        switch (parsed.problem()) {
            case BLANK -> throw cmds.fail(source, "messaging.blank");
            case INVALID_DURATION -> throw cmds.fail(source, "core.invalid-duration",
                    Msg.unparsed("input", parsed.detail()));
            case SILENT_NOT_ALLOWED -> throw cmds.fail(source, "messaging.no-permission-flag",
                    Msg.unparsed("flag", "-s"), Msg.unparsed("permission", PERM_SILENT));
            case NONE -> {
            }
        }
        return parsed;
    }

    private String adminText(CommandSourceStack source, String raw) throws CommandSyntaxException {
        int maximum = config().clampAdminLength();
        if (raw.length() > maximum) {
            throw cmds.fail(source, "messaging.too-long", Msg.number("max", maximum));
        }
        return raw;
    }

    private void validateTemplate(CommandSourceStack source, String template) throws CommandSyntaxException {
        try {
            ctx.messages().parseTrusted(template, Msg.unparsed("player", "player"));
        } catch (RuntimeException e) {
            throw cmds.fail(source, "messaging.invalid-minimessage");
        }
    }

    private Component render(String template, Player target) {
        return ctx.messages().parseTrusted(template, Msg.unparsed("player", target.getName()));
    }

    /** Normalises untrusted single-line text and caps its length. */
    static String untrustedText(String raw, int maximum) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            return "";
        }
        int limit = Math.max(1, maximum);
        StringBuilder cleaned = new StringBuilder(Math.min(text.length(), limit));
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                if (!cleaned.isEmpty() && cleaned.charAt(cleaned.length() - 1) != ' ') {
                    cleaned.append(' ');
                }
            } else {
                cleaned.append(c);
            }
        }
        String normalized = cleaned.toString().strip();
        if (normalized.length() <= limit) {
            return normalized;
        }
        int end = limit;
        if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end).strip();
    }

    private String senderName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : config().consoleName;
    }

    private String date(long epochMillis) {
        return dates.format(Instant.ofEpochMilli(epochMillis));
    }

    // ---- targets and action bars ----

    private List<Player> targets(CommandSourceStack source, CommandContext<CommandSourceStack> c, boolean all)
            throws CommandSyntaxException {
        CommandSender sender = source.getSender();
        List<Player> targets = new ArrayList<>();
        if (all) {
            for (AtriumUser user : ctx.users().online()) {
                Optional<Player> online = user.player();
                online.ifPresent(player -> {
                    if (canSee(sender, player)) {
                        targets.add(player);
                    }
                });
            }
            targets.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        } else {
            Player target = cmds.argPlayer(c, "target");
            if (!canSee(sender, target)) {
                throw cmds.fail(source, "messaging.target-hidden");
            }
            targets.add(target);
        }
        if (targets.isEmpty()) {
            throw cmds.fail(source, "messaging.no-targets");
        }
        return targets;
    }

    private boolean canSee(CommandSender sender, Player target) {
        if (!(sender instanceof Player viewer)) {
            return true;
        }
        return ctx.services().get(VanishService.class).map(service -> {
            try {
                return service.canSee(viewer, target);
            } catch (RuntimeException e) {
                ctx.logger().warn("Could not check vanish visibility of {}", target.getName(), e);
                return true;
            }
        }).orElse(true);
    }

    private void scheduleActionBar(Player target, String template, int seconds) {
        int interval = config().clampActionBarInterval();
        ctx.scheduler().entity(target, () -> {
            if (!target.isOnline()) {
                return;
            }
            Component bar;
            try {
                bar = render(template, target);
            } catch (RuntimeException e) {
                ctx.logger().warn("Invalid action bar MiniMessage for {}", target.getName(), e);
                return;
            }
            target.sendActionBar(bar);
            if (seconds <= 1) {
                return;
            }
            long end = System.currentTimeMillis() + seconds * 1000L;
            TaskHandle[] handle = {TaskHandle.NOOP};
            handle[0] = ctx.track(ctx.scheduler().entityTimer(target, () -> {
                if (!target.isOnline() || System.currentTimeMillis() >= end) {
                    handle[0].cancel();
                    return;
                }
                target.sendActionBar(bar);
            }, null, interval, interval));
        }, null);
    }

    // ---- helpop ----

    private List<Player> helpOpRecipients(CommandSender sender) {
        UUID senderId = sender instanceof Player player ? player.getUniqueId() : null;
        List<Player> recipients = new ArrayList<>();
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> online = user.player();
            online.ifPresent(player -> {
                if ((senderId == null || !player.getUniqueId().equals(senderId))
                        && player.hasPermission(PERM_HELPOP_RECEIVE)) {
                    // Vanished staff still receive help requests; only the count shown to the sender hides them.
                    recipients.add(player);
                }
            });
        }
        recipients.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return recipients;
    }

    private void sendHelpOp(Player recipient, CommandSender sender, String name, String text) {
        ctx.scheduler().entity(recipient, () -> {
            if (!recipient.isOnline() || !recipient.hasPermission(PERM_HELPOP_RECEIVE)) {
                return;
            }
            boolean clickable = config().helpOpClickableTeleport && sender instanceof Player player
                    && recipient.hasPermission(PERM_TP);
            if (clickable) {
                Player sourcePlayer = (Player) sender;
                Component tp = Component.text("[TP]", NamedTextColor.GREEN)
                        .clickEvent(ClickEvent.runCommand("/tp " + sourcePlayer.getName()))
                        .hoverEvent(HoverEvent.showText(Component.text("Teleport to " + sourcePlayer.getName())));
                ctx.messages().send(recipient, "messaging.helpop-format-tp",
                        Msg.unparsed("player", name), Msg.unparsed("message", text),
                        Msg.component("tp", tp));
            } else {
                ctx.messages().send(recipient, "messaging.helpop-format",
                        Msg.unparsed("player", name), Msg.unparsed("message", text));
            }
        }, null);
    }

    private void subscribeHelpOp() {
        Optional<ProxyService> proxy = ctx.services().get(ProxyService.class);
        if (proxy.isEmpty()) {
            return;
        }
        try {
            helpOpSubscription = proxy.get().subscribe(HELPOP_TOPIC, this::receiveHelpOp);
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not subscribe to the helpop proxy topic", e);
        }
    }

    private boolean publishHelpOp(String player, String text) {
        Optional<ProxyService> proxy = ctx.services().get(ProxyService.class);
        if (proxy.isEmpty()) {
            return false;
        }
        try {
            HelpOpPayload payload = new HelpOpPayload(proxy.get().serverName(), player, text);
            proxy.get().publish(HELPOP_TOPIC, payload.encode());
            return true;
        } catch (Exception e) {
            ctx.logger().warn("Could not publish a helpop request to the proxy", e);
            return false;
        }
    }

    private void receiveHelpOp(ProxyMessage message) {
        Optional<ProxyService> proxy = ctx.services().get(ProxyService.class);
        String own = proxy.map(ProxyService::serverName).orElse("");
        if (!own.isBlank() && own.equalsIgnoreCase(message.server())) {
            return; // defensive: the proxy already drops our own echo
        }
        Optional<HelpOpPayload> decoded = HelpOpPayload.decode(message.payload());
        if (decoded.isEmpty()) {
            ctx.logger().warn("Ignored a malformed helpop payload from server '{}'", message.server());
            return;
        }
        HelpOpPayload payload = decoded.get();
        String server = message.server().isBlank() ? payload.server() : message.server();
        String text = untrustedText(payload.message(), config().clampHelpOpLength());
        if (text.isBlank() || payload.player().isBlank()) {
            return;
        }
        for (AtriumUser user : ctx.users().online()) {
            Optional<Player> online = user.player();
            online.ifPresent(player -> {
                if (!player.hasPermission(PERM_HELPOP_RECEIVE)) {
                    return;
                }
                ctx.scheduler().entity(player, () -> {
                    if (player.isOnline() && player.hasPermission(PERM_HELPOP_RECEIVE)) {
                        ctx.messages().send(player, "messaging.helpop-remote-format",
                                Msg.unparsed("server", server),
                                Msg.unparsed("player", payload.player()),
                                Msg.unparsed("message", text));
                    }
                }, null);
            });
        }
        ctx.messages().send(ctx.plugin().getServer().getConsoleSender(), "messaging.helpop-remote-console",
                Msg.unparsed("server", server), Msg.unparsed("player", payload.player()),
                Msg.unparsed("message", text));
    }

    // ---- alerts on join ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        long now = System.currentTimeMillis();
        alerts.forPlayer(joined.getUniqueId(), now).thenAccept(found -> {
            if (found.isEmpty()) {
                return;
            }
            for (AtriumUser user : ctx.users().online()) {
                Optional<Player> online = user.player();
                online.ifPresent(staff -> {
                    if (staff.getUniqueId().equals(joined.getUniqueId())
                            || !staff.hasPermission(PERM_ALERT_RECEIVE)) {
                        return;
                    }
                    ctx.scheduler().entity(staff, () -> {
                        if (!staff.isOnline() || !staff.hasPermission(PERM_ALERT_RECEIVE)) {
                            return;
                        }
                        ctx.messages().send(staff, "messaging.alert-join-header",
                                Msg.unparsed("player", joined.getName()), Msg.number("count", found.size()));
                        for (AlertStore.Alert alert : found) {
                            ctx.messages().send(staff, "messaging.alert-join-entry",
                                    Msg.number("id", alert.id()),
                                    Msg.unparsed("reason", alert.reason()),
                                    Msg.unparsed("by", alert.createdBy()),
                                    Msg.unparsed("date", date(alert.created())));
                        }
                    }, null);
                });
            }
        }).exceptionally(error -> {
            ctx.logger().warn("Could not load alerts for {}", joined.getName(), error);
            return null;
        });
    }

    // ---- feedback ----

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }

    private void failed(CommandSender sender, String action, Throwable error) {
        ctx.logger().warn("Messaging could not {}", action, error);
        reply(sender, "core.error");
    }
}
