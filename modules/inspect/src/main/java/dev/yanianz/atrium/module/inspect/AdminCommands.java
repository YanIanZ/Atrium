package dev.yanianz.atrium.module.inspect;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.entity.Player;

/**
 * Administrative diagnostics and data tools: {@code /util} dumps, {@code /sameip}, {@code /removeuser} and
 * {@code /resetdbfields}. The two destructive commands only remove Atrium's own rows, refuse a loaded
 * account, and require a confirmation.
 */
final class AdminCommands {
    static final String PERM_UTIL = "atrium.util";
    static final String PERM_SAMEIP = "atrium.sameip";
    static final String PERM_SAMEIP_IP = "atrium.sameip.ip";
    static final String PERM_REMOVEUSER = "atrium.removeuser";
    static final String PERM_RESETDBFIELDS = "atrium.resetdbfields";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<InspectConfig> config;
    private final UserDataAdmin data;
    private final PendingActions pending = new PendingActions(System::currentTimeMillis);

    AdminCommands(ModuleContext ctx, ConfigHandle<InspectConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.data = new UserDataAdmin(ctx);
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("util", PERM_UTIL)
                .executes(this::utilUsage)
                .then(Commands.argument("kind", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> List.of("commands", "permissions", "materials", "info")))
                        .executes(this::util))
                .build(), "Dump commands, permissions or materials to a file"));

        ctx.command(CommandSpec.of(Cmds.literal("sameip", PERM_SAMEIP)
                .executes(this::sameIp)
                .build(), "Group online players that share an IP address"));

        ctx.command(CommandSpec.of(Cmds.literal("removeuser", PERM_REMOVEUSER)
                .executes(this::removeUsage)
                .then(Cmds.literal("duplicates")
                        .executes(c -> duplicates(c, false))
                        .then(Cmds.literal("confirm").executes(c -> duplicates(c, true))))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(c -> remove(c, false))
                        .then(Cmds.literal("confirm").executes(c -> remove(c, true))))
                .build(), "Delete every Atrium row of an account (or duplicate names)"));

        ctx.command(CommandSpec.of(Cmds.literal("resetdbfields", PERM_RESETDBFIELDS)
                .executes(this::resetUsage)
                .then(Commands.argument("group", StringArgumentType.word())
                        .suggests(Cmds.suggest(AdminCommands::groupNames))
                        .executes(this::resetUsage)
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(Cmds.knownPlayers(ctx.users()))
                                .executes(c -> reset(c, false))
                                .then(Cmds.literal("confirm").executes(c -> reset(c, true)))))
                .build(), "Reset one dataset (homes, kits, playtime, ...) of an offline account"));
    }

    // ---- /util -----------------------------------------------------------------------------------

    private int utilUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "inspect.util-usage");
        return Cmds.OK;
    }

    private int util(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String kind = StringArgumentType.getString(c, "kind").toLowerCase(Locale.ROOT);
        String generated = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        String content = switch (kind) {
            case "commands" -> UtilDumps.commands(generated, commandRows());
            case "permissions" -> UtilDumps.permissions(generated, permissionRows());
            case "materials" -> UtilDumps.materials(generated, materialRows());
            case "info" -> UtilDumps.info(generated, infoValues());
            default -> throw cmds.fail(c.getSource(), "inspect.util-unknown", Msg.unparsed("kind", kind));
        };
        Path directory = ctx.dataDirectory();
        Path file = directory.resolve("util-" + kind + ".txt");
        ctx.scheduler().supplyIo(() -> {
            Files.createDirectories(directory);
            Files.writeString(file, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return null;
        }).whenComplete((value, error) -> {
            if (error != null) {
                ctx.logger().warn("Writing the /util {} dump failed", kind, error);
                onSender(sender, () -> ctx.messages().send(sender, "core.error"));
                return;
            }
            long lines = content.lines().count();
            onSender(sender, () -> ctx.messages().send(sender, "inspect.util-written",
                    Msg.unparsed("kind", kind), Msg.unparsed("file", file.toString()),
                    Msg.number("lines", lines)));
        });
        return Cmds.OK;
    }

    private List<UtilDumps.CommandRow> commandRows() {
        CommandMap map = Bukkit.getCommandMap();
        Map<Command, Set<String>> aliases = new IdentityHashMap<>();
        for (Map.Entry<String, Command> entry : map.getKnownCommands().entrySet()) {
            aliases.computeIfAbsent(entry.getValue(), key -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                    .add(entry.getKey());
        }
        List<UtilDumps.CommandRow> rows = new ArrayList<>();
        for (Map.Entry<Command, Set<String>> entry : aliases.entrySet()) {
            Command command = entry.getKey();
            Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            names.addAll(entry.getValue());
            names.addAll(command.getAliases());
            names.removeIf(name -> name.equalsIgnoreCase(command.getName()));
            String owner = command instanceof PluginIdentifiableCommand identifiable
                    ? identifiable.getPlugin().getName() : "server";
            rows.add(new UtilDumps.CommandRow(command.getName(), List.copyOf(names), owner,
                    command.getPermission() == null ? "" : command.getPermission(),
                    command.getUsage() == null ? "" : command.getUsage(),
                    command.getDescription() == null ? "" : command.getDescription()));
        }
        return rows;
    }

    private List<UtilDumps.PermissionRow> permissionRows() {
        return Bukkit.getPluginManager().getPermissions().stream()
                .map(permission -> new UtilDumps.PermissionRow(permission.getName(),
                        permission.getDefault().name().toLowerCase(Locale.ROOT),
                        permission.getDescription() == null ? "" : permission.getDescription()))
                .toList();
    }

    private List<UtilDumps.MaterialRow> materialRows() {
        List<UtilDumps.MaterialRow> rows = new ArrayList<>();
        for (Material material : Material.values()) {
            // Only the enum name is read. getKey(), isAir(), isBlock() and isItem() may resolve a legacy
            // material and initialize CraftLegacy's datafixer, which stalls a live region thread for seconds.
            rows.add(new UtilDumps.MaterialRow(material.name()));
        }
        return rows;
    }

    private Map<String, String> infoValues() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("server", Bukkit.getName() + " " + Bukkit.getVersion());
        values.put("bukkit", Bukkit.getBukkitVersion());
        values.put("minecraft", Bukkit.getMinecraftVersion());
        values.put("java", System.getProperty("java.version", "?"));
        values.put("players", Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers());
        values.put("plugins", Integer.toString(Bukkit.getPluginManager().getPlugins().length));
        values.put("atrium", ctx.plugin().getPluginMeta().getVersion());
        values.put("modules", ctx.plugin().getPluginMeta().getDescription() == null
                ? "" : ctx.plugin().getPluginMeta().getDescription());
        return values;
    }

    // ---- /sameip ---------------------------------------------------------------------------------

    private int sameIp(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        List<CompletableFuture<SameIp.Session>> sessions = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            sessions.add(ctx.scheduler().supplyOnEntity(player, () -> new SameIp.Session(
                    player.getUniqueId(), player.getName(), addressOf(player))));
        }
        CompletableFuture.allOf(sessions.toArray(CompletableFuture[]::new)).whenComplete((value, error) -> {
            if (error != null) {
                ctx.logger().warn("Reading online addresses for /sameip failed", error);
                onSender(sender, () -> ctx.messages().send(sender, "core.error"));
                return;
            }
            List<SameIp.Session> collected = sessions.stream()
                    .map(CompletableFuture::join)
                    .toList();
            List<SameIp.Group> groups = SameIp.group(collected);
            onSender(sender, () -> sendSameIp(sender, groups));
        });
        return Cmds.OK;
    }

    private static String addressOf(Player player) {
        if (player.getAddress() == null || player.getAddress().getAddress() == null) {
            return "";
        }
        return player.getAddress().getAddress().getHostAddress();
    }

    private void sendSameIp(CommandSender sender, List<SameIp.Group> groups) {
        if (groups.isEmpty()) {
            ctx.messages().send(sender, "inspect.sameip-empty");
            return;
        }
        boolean showAddress = sender.hasPermission(PERM_SAMEIP_IP);
        long accounts = groups.stream().mapToLong(group -> group.sessions().size()).sum();
        ctx.messages().send(sender, "inspect.sameip-header",
                Msg.number("groups", groups.size()), Msg.number("accounts", accounts));
        for (SameIp.Group group : groups) {
            String address = showAddress ? group.address() : InspectFormat.maskAddress(group.address());
            ctx.messages().send(sender, "inspect.sameip-group",
                    Msg.unparsed("address", address), Msg.number("count", group.sessions().size()));
            for (SameIp.Session session : group.sessions()) {
                ctx.messages().send(sender, "inspect.sameip-entry", Msg.unparsed("player", session.name()));
            }
        }
    }

    // ---- /removeuser -----------------------------------------------------------------------------

    private int removeUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "inspect.removeuser-usage");
        return Cmds.OK;
    }

    private int remove(CommandContext<CommandSourceStack> c, boolean confirmLiteral) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        String key = "removeuser|" + sender.getName() + "|" + name.toLowerCase(Locale.ROOT);
        data.account(name).whenComplete((found, error) -> {
            if (error != null) {
                report(sender, "Resolving " + name + " for /removeuser", error);
                return;
            }
            if (found.isEmpty()) {
                onSender(sender, () -> ctx.messages().send(sender, "core.user-not-found",
                        Msg.unparsed("name", name)));
                return;
            }
            UserDataAdmin.AccountRow account = found.get();
            if (loaded(account.uuid())) {
                onSender(sender, () -> ctx.messages().send(sender, "inspect.removeuser-loaded",
                        Msg.unparsed("player", account.name())));
                return;
            }
            if (!approved(sender, key, confirmLiteral, "inspect.removeuser-confirm",
                    Msg.unparsed("player", account.name()),
                    Msg.number("seconds", config.get().confirmSeconds()))) {
                return;
            }
            data.purge(account.uuid(), UserDataAdmin.Group.ALL, true).whenComplete((result, failure) -> {
                if (failure != null) {
                    report(sender, "Removing " + account.name(), failure);
                    return;
                }
                onSender(sender, () -> ctx.messages().send(sender, "inspect.removeuser-done",
                        Msg.unparsed("player", account.name()),
                        Msg.number("tables", result.tables()), Msg.number("rows", result.rows())));
            });
        });
        return Cmds.OK;
    }

    private int duplicates(CommandContext<CommandSourceStack> c, boolean confirmLiteral) {
        CommandSender sender = c.getSource().getSender();
        String key = "removeuser|" + sender.getName() + "|duplicates";
        data.duplicates().whenComplete((groups, error) -> {
            if (error != null) {
                report(sender, "Scanning duplicate accounts", error);
                return;
            }
            if (groups.isEmpty()) {
                onSender(sender, () -> ctx.messages().send(sender, "inspect.removeuser-duplicates-none"));
                return;
            }
            long removable = groups.stream().mapToLong(group -> group.removed().size()).sum();
            long skipped = groups.stream().mapToLong(group -> group.skipped().size()).sum();
            if (!approved(sender, key, confirmLiteral, "inspect.removeuser-duplicates-confirm",
                    Msg.number("groups", groups.size()), Msg.number("accounts", removable),
                    Msg.number("skipped", skipped), Msg.number("seconds", config.get().confirmSeconds()))) {
                return;
            }
            List<CompletableFuture<UserDataAdmin.PurgeResult>> purges = groups.stream()
                    .map(data::purgeDuplicates)
                    .toList();
            CompletableFuture.allOf(purges.toArray(CompletableFuture[]::new)).whenComplete((value, failure) -> {
                if (failure != null) {
                    report(sender, "Removing duplicate accounts", failure);
                    return;
                }
                long rows = purges.stream().mapToLong(future -> future.join().rows()).sum();
                onSender(sender, () -> ctx.messages().send(sender, "inspect.removeuser-duplicates-done",
                        Msg.number("groups", groups.size()), Msg.number("accounts", removable),
                        Msg.number("rows", rows)));
                if (skipped > 0) {
                    onSender(sender, () -> ctx.messages().send(sender,
                            "inspect.removeuser-duplicates-skipped", Msg.number("skipped", skipped)));
                }
            });
        });
        return Cmds.OK;
    }

    // ---- /resetdbfields --------------------------------------------------------------------------

    private int resetUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "inspect.reset-usage",
                Msg.unparsed("groups", String.join(", ", groupNames())));
        return Cmds.OK;
    }

    private int reset(CommandContext<CommandSourceStack> c, boolean confirmLiteral) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String groupRaw = StringArgumentType.getString(c, "group");
        UserDataAdmin.Group group = UserDataAdmin.Group.parse(groupRaw);
        if (group == null) {
            throw cmds.fail(c.getSource(), "inspect.reset-unknown", Msg.unparsed("group", groupRaw));
        }
        String name = StringArgumentType.getString(c, "player");
        String key = "reset|" + sender.getName() + "|" + group.name() + "|" + name.toLowerCase(Locale.ROOT);
        data.account(name).whenComplete((found, error) -> {
            if (error != null) {
                report(sender, "Resolving " + name + " for /resetdbfields", error);
                return;
            }
            if (found.isEmpty()) {
                onSender(sender, () -> ctx.messages().send(sender, "core.user-not-found",
                        Msg.unparsed("name", name)));
                return;
            }
            UserDataAdmin.AccountRow account = found.get();
            if (loaded(account.uuid())) {
                onSender(sender, () -> ctx.messages().send(sender, "inspect.reset-loaded",
                        Msg.unparsed("player", account.name())));
                return;
            }
            if (!approved(sender, key, confirmLiteral, "inspect.reset-confirm",
                    Msg.unparsed("group", group.name().toLowerCase(Locale.ROOT)),
                    Msg.unparsed("player", account.name()),
                    Msg.number("seconds", config.get().confirmSeconds()))) {
                return;
            }
            data.purge(account.uuid(), group, false).whenComplete((result, failure) -> {
                if (failure != null) {
                    report(sender, "Resetting " + group + " of " + account.name(), failure);
                    return;
                }
                onSender(sender, () -> ctx.messages().send(sender, "inspect.reset-done",
                        Msg.unparsed("group", group.name().toLowerCase(Locale.ROOT)),
                        Msg.unparsed("player", account.name()),
                        Msg.number("tables", result.tables()), Msg.number("rows", result.rows())));
            });
        });
        return Cmds.OK;
    }

    private boolean loaded(UUID uuid) {
        return Bukkit.getPlayer(uuid) != null || ctx.users().cached(uuid).isPresent();
    }

    private static List<String> groupNames() {
        return java.util.Arrays.stream(UserDataAdmin.Group.values())
                .map(group -> group.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toList());
    }

    // ---- confirmation ----------------------------------------------------------------------------

    /**
     * Two-step guard shared by the destructive commands. When the literal {@code confirm} was typed the
     * request must already be pending; otherwise the first run records it and the second run (same command
     * inside the window) applies the change.
     */
    private boolean approved(CommandSender sender, String key, boolean confirmLiteral,
            String confirmKey, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        if (confirmLiteral) {
            if (!pending.confirm(key)) {
                onSender(sender, () -> ctx.messages().send(sender, "inspect.confirm-expired",
                        Msg.number("seconds", config.get().confirmSeconds())));
                return false;
            }
            return true;
        }
        if (pending.confirm(key)) {
            return true;
        }
        pending.request(key, config.get().confirmSeconds() * 1000L);
        net.kyori.adventure.text.Component prompt = ctx.messages().render(sender, confirmKey, tags);
        onSender(sender, () -> sender.sendMessage(prompt));
        return false;
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private void report(CommandSender sender, String action, Throwable error) {
        ctx.logger().warn("{} failed for {}", action, sender.getName(), error);
        onSender(sender, () -> ctx.messages().send(sender, "core.error"));
    }
}
