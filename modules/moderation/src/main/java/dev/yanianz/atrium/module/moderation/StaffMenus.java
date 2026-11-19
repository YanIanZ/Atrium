package dev.yanianz.atrium.module.moderation;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.api.proxy.ProxyMessage;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.menu.Button;
import dev.yanianz.atrium.core.menu.ItemBuilder;
import dev.yanianz.atrium.core.menu.Menu;
import dev.yanianz.atrium.core.menu.PagedMenu;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * The staff menus of the moderation module: {@code /punish <player>} (preset punishments, one click each) and
 * the report queue ({@code /report <player> [reason]} for everyone, {@code /reports} for staff).
 *
 * <p>A preset runs its command as the staff member, so permissions, exemptions and warning escalation are the
 * ones of the ordinary commands. Reports live in the module's database; with a shared database and the proxy
 * module a report made on one server is announced to the staff of every server and handled from any of them.
 */
final class StaffMenus {
    static final String PERM_PUNISH = ModerationCommands.PERM + "punish";
    static final String PERM_REPORT = ModerationCommands.PERM + "report";
    static final String PERM_REPORTS = ModerationCommands.PERM + "reports";
    private static final String TOPIC = "moderation-reports";
    /** Java names, and Bedrock names behind a one-character prefix. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.*+-]{1,17}");
    private static final int[] COLUMNS = {10, 12, 14, 16};
    private static final String[] CATEGORIES = {"ban", "mute", "warn", "kick"};
    private static final Material[] HEADERS = {Material.TNT, Material.REDSTONE, Material.PAPER, Material.LEATHER_BOOTS};

    private final ModuleContext ctx;
    private final Messages messages;
    private final ConfigHandle<ModerationConfig> config;
    private final ReportStore reports;
    private final Map<UUID, Long> lastReport = new ConcurrentHashMap<>();
    private volatile ProxyService.@Nullable Subscription subscription;

    StaffMenus(ModuleContext ctx, ConfigHandle<ModerationConfig> config, ReportStore reports) {
        this.ctx = ctx;
        this.messages = ctx.messages();
        this.config = config;
        this.reports = reports;
    }

    void register() {
        ctx.permission(PERM_PUNISH, PermissionDefault.OP, "Open the punishment menu of a player");
        ctx.permission(PERM_REPORT, PermissionDefault.TRUE, "Report a player to the staff");
        ctx.permission(PERM_REPORTS, PermissionDefault.OP, "See and handle player reports");
        ctx.command(CommandSpec.of(Cmds.literal("punish", PERM_PUNISH)
                .executes(c -> usage(c, "moderation.punish-menu.usage"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(Cmds.suggest(StaffMenus::onlineNames))
                        .executes(this::punish)).build(), "Open the punishment menu of a player"));
        ctx.command(CommandSpec.of(Cmds.literal("report", PERM_REPORT)
                .executes(c -> usage(c, "moderation.reports.usage"))
                .then(Commands.argument("player", StringArgumentType.word()).suggests(Cmds.suggest(StaffMenus::onlineNames))
                        .executes(this::report)
                        .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(this::report)))
                .build(), "Report a player to the staff"));
        ctx.command(CommandSpec.of(Cmds.literal("reports", PERM_REPORTS).executes(this::queue).build(),
                "See and handle player reports"));
        ctx.services().get(ProxyService.class).ifPresent(proxy -> subscription = proxy.subscribe(TOPIC, this::receive));
    }

    void disable() {
        ProxyService.Subscription current = subscription;
        subscription = null;
        if (current != null) {
            current.close();
        }
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        messages.send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    // ---- /punish ----

    private int punish(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (!(sender instanceof Player viewer)) {
            messages.send(sender, "moderation.punish-menu.players-only");
            return Cmds.OK;
        }
        openPunish(viewer, StringArgumentType.getString(c, "player"));
        return Cmds.OK;
    }

    /** Resolves the name off the viewer's thread and opens the menu on it. */
    private void openPunish(Player viewer, String name) {
        if (!NAME.matcher(name).matches()) {
            messages.send(viewer, "core.user-not-found", Msg.unparsed("name", name));
            return;
        }
        ctx.users().resolveAccount(name, config.get().resolveUnknownNames).whenComplete((found, error) -> {
            if (error != null) {
                failed(viewer, "/punish", error);
                return;
            }
            if (found.isEmpty() || !NAME.matcher(found.get().name()).matches()) {
                messages.send(viewer, "core.user-not-found", Msg.unparsed("name", name));
                return;
            }
            String target = found.get().name();
            ctx.scheduler().entity(viewer, () -> ctx.menus().open(viewer, punishMenu(viewer, target)), null);
        });
    }

    private Menu punishMenu(Player viewer, String target) {
        ModerationConfig.PunishMenu cfg = config.get().punishMenu;
        TagResolver player = Msg.unparsed("player", target);
        Menu.Builder menu = Menu.builder(messages.render(viewer, "moderation.punish-menu.title", player), 6);
        menu.item(4, ItemBuilder.of(Material.PLAYER_HEAD)
                .name(messages.render(viewer, "moderation.punish-menu.target", player))
                .lore(messages.render(viewer, "moderation.punish-menu.target-lore", player)).build());
        int shown = 0;
        for (int column = 0; column < CATEGORIES.length; column++) {
            String category = CATEGORIES[column];
            menu.item(COLUMNS[column], ItemBuilder.of(HEADERS[column])
                    .name(messages.render(viewer, "moderation.punish-menu.category." + category))
                    .lore(messages.render(viewer, "moderation.punish-menu.category-lore")).hideDetails().build());
            int row = 1;
            for (ModerationConfig.Preset preset : cfg.presets) {
                if (preset == null || preset.command == null || preset.command.isBlank()
                        || !category.equalsIgnoreCase(preset.category) || row > 4) {
                    continue;
                }
                String root = preset.command.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
                if (root.startsWith("/")) {
                    root = root.substring(1);
                }
                if (!viewer.hasPermission(ModerationCommands.PERM + root)) {
                    continue;
                }
                String command = preset.command.trim().replace("{player}", target);
                String line = command.startsWith("/") ? command.substring(1) : command;
                TagResolver label = Msg.unparsed("label", preset.label == null || preset.label.isBlank() ? line : preset.label);
                TagResolver description = Msg.unparsed("description", preset.description == null ? "" : preset.description);
                TagResolver shownCommand = Msg.unparsed("command", "/" + line);
                menu.item(COLUMNS[column] + 9 * row, ItemBuilder.of(Material.PAPER)
                        .name(messages.render(viewer, "moderation.punish-menu.preset", label))
                        .lore(messages.render(viewer, "moderation.punish-menu.preset-lore", label, description,
                                shownCommand, player)).build(), click -> {
                    Player staff = click.player();
                    if (!cfg.confirm) {
                        click.close();
                        staff.performCommand(line);
                        return;
                    }
                    ctx.menus().confirm(staff,
                            messages.render(staff, "moderation.punish-menu.confirm-title", player),
                            List.of(messages.render(staff, "moderation.punish-menu.confirm-details", label, player,
                                    shownCommand)),
                            confirmed -> {
                                confirmed.close();
                                confirmed.player().performCommand(line);
                            }, null);
                });
                row++;
                shown++;
            }
        }
        if (shown == 0) {
            menu.item(31, ItemBuilder.of(Material.BARRIER)
                    .name(messages.render(viewer, "moderation.punish-menu.nothing")).build());
        }
        if (viewer.hasPermission(ModerationCommands.PERM + "history")) {
            menu.item(45, ItemBuilder.of(Material.BOOK)
                    .name(messages.render(viewer, "moderation.punish-menu.history", player)).build(), click -> {
                click.close();
                click.player().performCommand("history " + target);
            });
        }
        if (viewer.hasPermission(ModerationCommands.PERM + "note")) {
            menu.item(53, ItemBuilder.of(Material.WRITABLE_BOOK)
                    .name(messages.render(viewer, "moderation.punish-menu.notes", player)).build(), click -> {
                click.close();
                click.player().performCommand("note " + target);
            });
        }
        return menu.build();
    }

    // ---- /report ----

    private int report(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (!(sender instanceof Player reporter)) {
            messages.send(sender, "moderation.punish-menu.players-only");
            return Cmds.OK;
        }
        String name = StringArgumentType.getString(c, "player");
        String reason = Cmds.hasArgument(c, "reason") ? StringArgumentType.getString(c, "reason").trim() : "";
        if (!NAME.matcher(name).matches()) {
            messages.send(reporter, "core.user-not-found", Msg.unparsed("name", name));
            return Cmds.OK;
        }
        if (name.equalsIgnoreCase(reporter.getName())) {
            messages.send(reporter, "moderation.reports.self");
            return Cmds.OK;
        }
        if (reason.isEmpty()) {
            List<String> reasons = config.get().reports.reasons;
            if (reasons == null || reasons.isEmpty()) {
                messages.send(reporter, "moderation.reports.usage");
                return Cmds.OK;
            }
            ctx.menus().open(reporter, reasonMenu(reporter, name, reasons));
            return Cmds.OK;
        }
        submit(reporter, name, reason);
        return Cmds.OK;
    }

    private Menu reasonMenu(Player reporter, String target, List<String> reasons) {
        TagResolver player = Msg.unparsed("player", target);
        int rows = Math.min(6, Math.max(3, (Math.min(reasons.size(), 28) + 6) / 7 + 2));
        Menu.Builder menu = Menu.builder(messages.render(reporter, "moderation.reports.reason-title", player), rows);
        int index = 0;
        for (String reason : reasons) {
            if (reason == null || reason.isBlank() || index >= (rows - 2) * 7) {
                continue;
            }
            int slot = 9 * (1 + index / 7) + 1 + index % 7;
            String text = reason.trim();
            menu.item(slot, ItemBuilder.of(Material.PAPER)
                    .name(messages.render(reporter, "moderation.reports.reason-entry", Msg.unparsed("reason", text)))
                    .lore(messages.render(reporter, "moderation.reports.reason-lore", player)).build(), click -> {
                click.close();
                submit(click.player(), target, text);
            });
            index++;
        }
        return menu.build();
    }

    private void submit(Player reporter, String name, String rawReason) {
        ModerationConfig.Reports cfg = config.get().reports;
        long now = System.currentTimeMillis();
        Long last = lastReport.get(reporter.getUniqueId());
        long wait = last == null ? 0 : cfg.cooldownSeconds * 1000L - (now - last);
        if (wait > 0 && !reporter.hasPermission(PERM_REPORTS)) {
            messages.send(reporter, "moderation.reports.cooldown", Msg.number("seconds", (wait + 999) / 1000));
            return;
        }
        int max = Math.max(8, Math.min(256, cfg.maxReasonLength));
        String reason = rawReason.length() > max ? rawReason.substring(0, max) : rawReason;
        UUID reporterId = reporter.getUniqueId();
        String reporterName = reporter.getName();
        String server = serverName();
        ctx.users().resolveAccount(name, false).thenCompose(found -> {
            if (found.isEmpty()) {
                messages.send(reporter, "core.user-not-found", Msg.unparsed("name", name));
                return CompletableFuture.<Void>completedFuture(null);
            }
            UUID target = found.get().uuid();
            String targetName = found.get().name();
            if (target.equals(reporterId)) {
                messages.send(reporter, "moderation.reports.self");
                return CompletableFuture.<Void>completedFuture(null);
            }
            return reports.openBy(reporterId).thenCompose(open -> {
                if (open >= Math.max(1, cfg.maxOpenPerPlayer)) {
                    messages.send(reporter, "moderation.reports.too-many", Msg.number("max", cfg.maxOpenPerPlayer));
                    return CompletableFuture.<Void>completedFuture(null);
                }
                return reports.duplicate(reporterId, target).thenCompose(duplicate -> {
                    if (duplicate) {
                        messages.send(reporter, "moderation.reports.duplicate", Msg.unparsed("player", targetName));
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                    lastReport.put(reporterId, now);
                    return reports.add(target, targetName, reporterId, reporterName, reason, server, Instant.now())
                            .thenAccept(id -> {
                                messages.send(reporter, "moderation.reports.submitted",
                                        Msg.unparsed("player", targetName), Msg.unparsed("reason", reason),
                                        Msg.number("id", id));
                                announce(id, targetName, reporterName, server, reason);
                                publish("new", String.valueOf(id), targetName, reporterName, server, reason);
                            });
                });
            });
        }).exceptionally(t -> failed(reporter, "/report", t));
    }

    private void announce(long id, String target, String reporter, String server, String reason) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(PERM_REPORTS)) {
                messages.send(staff, "moderation.reports.staff-notice", Msg.number("id", id),
                        Msg.unparsed("player", target), Msg.unparsed("reporter", reporter),
                        Msg.unparsed("server", server), Msg.unparsed("reason", reason));
            }
        }
    }

    // ---- /reports ----

    private int queue(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (!(sender instanceof Player viewer)) {
            messages.send(sender, "moderation.punish-menu.players-only");
            return Cmds.OK;
        }
        openQueue(viewer);
        return Cmds.OK;
    }

    private void openQueue(Player viewer) {
        reports.open(Math.max(1, Math.min(500, config.get().reports.queueSize))).whenComplete((list, error) -> {
            if (error != null) {
                failed(viewer, "/reports", error);
                return;
            }
            if (list.isEmpty()) {
                messages.send(viewer, "moderation.reports.empty");
                return;
            }
            ctx.scheduler().entity(viewer, () -> ctx.menus().open(viewer, PagedMenu.builder(ctx.menus(),
                            messages.render(viewer, "moderation.reports.queue-title", Msg.number("count", list.size())))
                    .rows(6)
                    .content(player -> list.stream().map(report -> entry(player, report)).toList())
                    .build()), null);
        });
    }

    private Button entry(Player viewer, ReportStore.Report report) {
        boolean claimed = ReportStore.CLAIMED.equals(report.status());
        TagResolver[] tags = {Msg.number("id", report.id()), Msg.unparsed("player", report.targetName()),
                Msg.unparsed("reporter", report.reporterName()), Msg.unparsed("reason", report.reason()),
                Msg.unparsed("server", report.server()), Msg.unparsed("age", age(report.created())),
                Msg.unparsed("handler", report.handlerName() == null ? "-" : report.handlerName())};
        return Button.of(ItemBuilder.of(claimed ? Material.WRITABLE_BOOK : Material.PAPER)
                        .name(messages.render(viewer, "moderation.reports.entry", tags))
                        .lore(messages.render(viewer, claimed ? "moderation.reports.entry-lore-claimed"
                                : "moderation.reports.entry-lore", tags))
                        .build(), click -> claim(click.player(), report))
                .onRight(click -> {
                    if (click.player().hasPermission(PERM_PUNISH)) {
                        openPunish(click.player(), report.targetName());
                    } else {
                        click.error();
                    }
                })
                .onShiftLeft(click -> finish(click.player(), report, ReportStore.RESOLVED))
                .onShiftRight(click -> finish(click.player(), report, ReportStore.REJECTED));
    }

    /** Marks the report as being looked at and goes to the reported player when they are on this server. */
    private void claim(Player staff, ReportStore.Report report) {
        reports.status(report.id(), ReportStore.CLAIMED, staff.getUniqueId(), staff.getName(), Instant.now())
                .whenComplete((changed, error) -> {
                    if (error != null) {
                        failed(staff, "/reports claim", error);
                        return;
                    }
                    if (!changed) {
                        messages.send(staff, "moderation.reports.already-closed", Msg.number("id", report.id()));
                        openQueue(staff);
                        return;
                    }
                    Player target = Bukkit.getPlayer(report.target());
                    messages.send(staff, target != null ? "moderation.reports.claimed-here" : "moderation.reports.claimed-away",
                            Msg.number("id", report.id()), Msg.unparsed("player", report.targetName()),
                            Msg.unparsed("server", report.server()));
                    ctx.scheduler().entity(staff, () -> {
                        ctx.menus().close(staff);
                        if (target != null && target != staff && staff.hasPermission("atrium.teleport.tp")) {
                            staff.performCommand("tp " + target.getName());
                        }
                    }, null);
                });
    }

    private void finish(Player staff, ReportStore.Report report, String status) {
        reports.status(report.id(), status, staff.getUniqueId(), staff.getName(), Instant.now())
                .whenComplete((changed, error) -> {
                    if (error != null) {
                        failed(staff, "/reports close", error);
                        return;
                    }
                    if (!changed) {
                        messages.send(staff, "moderation.reports.already-closed", Msg.number("id", report.id()));
                    } else {
                        boolean resolved = ReportStore.RESOLVED.equals(status);
                        messages.send(staff, resolved ? "moderation.reports.resolved" : "moderation.reports.rejected",
                                Msg.number("id", report.id()), Msg.unparsed("player", report.targetName()));
                        tellReporter(report.reporter(), report.targetName(), resolved);
                        publish("closed", report.reporter().toString(), report.targetName(), resolved ? "1" : "0");
                    }
                    openQueue(staff);
                });
    }

    private void tellReporter(UUID reporter, String target, boolean resolved) {
        Player online = Bukkit.getPlayer(reporter);
        if (online != null) {
            messages.send(online, resolved ? "moderation.reports.reporter-resolved" : "moderation.reports.reporter-rejected",
                    Msg.unparsed("player", target));
        }
    }

    // ---- other servers ----

    private void publish(String... fields) {
        ProxyService proxy = ctx.services().get(ProxyService.class).orElse(null);
        if (proxy == null) {
            return;
        }
        try {
            StringBuilder out = new StringBuilder();
            for (String field : fields) {
                if (out.length() > 0) {
                    out.append('\t');
                }
                out.append(field.replace('\t', ' ').replace('\n', ' '));
            }
            proxy.publish(TOPIC, out.toString().getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            ctx.logger().warn("Could not tell the other servers about a report", e);
        }
    }

    private void receive(ProxyMessage message) {
        try {
            if (message.payload() == null || message.payload().length > 2048 || message.server().equals(serverName())) {
                return;
            }
            String[] f = new String(message.payload(), StandardCharsets.UTF_8).split("\t", -1);
            if (f.length == 6 && f[0].equals("new")) {
                announce(Long.parseLong(f[1]), limit(f[2], 32), limit(f[3], 32), limit(f[4], 64), limit(f[5], 256));
            } else if (f.length == 4 && f[0].equals("closed")) {
                tellReporter(UUID.fromString(f[1]), limit(f[2], 32), f[3].equals("1"));
            }
        } catch (RuntimeException e) {
            ctx.logger().warn("Ignored a malformed report message from {}", message.server());
        }
    }

    private static String limit(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private String serverName() {
        return ctx.services().get(ProxyService.class).map(ProxyService::serverName).filter(n -> n != null && !n.isBlank())
                .orElse("server");
    }

    private static String age(Instant created) {
        long minutes = Math.max(0, Duration.between(created, Instant.now()).toMinutes());
        if (minutes < 60) {
            return minutes + "m";
        }
        if (minutes < 60 * 48) {
            return minutes / 60 + "h";
        }
        return minutes / 1440 + "d";
    }

    private Void failed(CommandSender sender, String what, Throwable t) {
        ctx.logger().error("{} by {} failed", what, sender.getName(), t);
        messages.send(sender, "core.error");
        return null;
    }
}
