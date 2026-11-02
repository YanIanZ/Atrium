package dev.yanianz.atrium.core;

import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.docs.DocsGenerator;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.Map;

/** {@code /atrium [version|modules|reload|docs|help|menu]} */
final class AtriumCommand {
    private final AtriumPlugin plugin;
    private final Cmds cmds;
    private final Messages messages;
    private final HelpCommand help;

    static final String ADMIN = "atrium.command.atrium";
    static final String HELP = "atrium.command.atrium.help";
    static final String MENU = "atrium.command.atrium.menu";
    static final String PROFILE = "atrium.command.atrium.profile";
    static final String PROFILE_OTHERS = "atrium.command.atrium.profile.others";

    AtriumCommand(AtriumPlugin plugin, Cmds cmds) {
        this.plugin = plugin;
        this.cmds = cmds;
        this.messages = cmds.messages();
        this.help = new HelpCommand(plugin, messages);
    }

    CommandSpec spec() {
        // The root is visible to admins and to everyone who may use help/menu; admin sub-commands still
        // require the root admin permission plus their own, exactly as before.
        var root = Cmds.literal("atrium")
                .requires(s -> has(s, ADMIN) || has(s, HELP) || has(s, MENU) || has(s, PROFILE))
                .executes(ctx -> has(ctx.getSource(), ADMIN) ? version(ctx) : help(ctx, 0))
                .then(admin("version", ADMIN).executes(this::version))
                .then(admin("modules", "atrium.command.atrium.modules").executes(this::modules))
                .then(admin("reload", "atrium.command.atrium.reload").executes(this::reload))
                .then(admin("docs", "atrium.command.atrium.docs").executes(this::docs))
                .then(Cmds.literal("help", HELP)
                        .executes(ctx -> help(ctx, 0))
                        .then(io.papermc.paper.command.brigadier.Commands
                                .argument("page|module", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    for (String module : help.visibleModules(ctx.getSource())) {
                                        if (module.startsWith(typed)) {
                                            builder.suggest(module);
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(this::helpArgument)))
                .then(Cmds.literal("menu", MENU).executes(this::menu))
                .then(Cmds.literal("profile", PROFILE)
                        .executes(ctx -> profile(ctx, null))
                        .then(io.papermc.paper.command.brigadier.Commands
                                .argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                .requires(s -> has(s, PROFILE_OTHERS))
                                .suggests((ctx, builder) -> {
                                    String typed = builder.getRemainingLowerCase();
                                    for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                                        if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(typed)) {
                                            builder.suggest(online.getName());
                                        }
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> profile(ctx, com.mojang.brigadier.arguments.StringArgumentType
                                        .getString(ctx, "player")))));
        return CommandSpec.of(root.build(), "Atrium administration and command help", "atr");
    }

    private static boolean has(CommandSourceStack source, String permission) {
        return source.getSender().hasPermission(permission);
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> admin(String name, String permission) {
        return Cmds.literal(name).requires(s -> has(s, ADMIN) && has(s, permission));
    }

    private int help(CommandContext<CommandSourceStack> ctx, int page) {
        help.sendPage(ctx.getSource(), page);
        return Cmds.OK;
    }

    private int helpArgument(CommandContext<CommandSourceStack> ctx) {
        String argument = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "page|module");
        int page = dev.yanianz.atrium.core.docs.HelpIndex.parsePage(argument);
        if (page >= 0) {
            help.sendPage(ctx.getSource(), page);
        } else {
            help.sendModule(ctx.getSource(), argument);
        }
        return Cmds.OK;
    }

    private int menu(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = cmds.player(ctx.getSource());
        help.openMenu(player, ctx.getSource());
        return Cmds.OK;
    }

    /**
     * {@code /atrium profile [player]}: profile integration status, the data that follows the active profile
     * and the active profile of a player. The profile lookup is asynchronous and never joined; the answer is
     * delivered on the sender's own thread.
     */
    private int profile(CommandContext<CommandSourceStack> ctx, @org.jspecify.annotations.Nullable String targetName) {
        var sender = ctx.getSource().getSender();
        var services = plugin.services();
        String registered = services.get(dev.yanianz.atrium.api.profile.ProfileScopedDataRegistry.class)
                .map(registry -> registry.all().stream().map(dev.yanianz.atrium.api.profile.ProfileScopedData::id)
                        .collect(java.util.stream.Collectors.joining(", ")))
                .orElse("");
        var service = services.get(dev.yanianz.atrium.api.profile.ActiveProfileService.class)
                .filter(dev.yanianz.atrium.api.profile.ActiveProfileService::enabled);
        if (service.isEmpty()) {
            messages.send(sender, "core.profile.unavailable");
            messages.send(sender, "core.profile.registered", Msg.unparsed("ids", registered.isEmpty() ? "-" : registered));
            return Cmds.OK;
        }
        var profiles = service.get();
        messages.send(sender, "core.profile.status");
        List<String> scoped = profiles.scopedData();
        messages.send(sender, "core.profile.scoped", Msg.unparsed("scoped", scoped.isEmpty() ? "-" : String.join(", ", scoped)));
        messages.send(sender, "core.profile.registered", Msg.unparsed("ids", registered.isEmpty() ? "-" : registered));
        messages.send(sender, "core.profile.economy", Msg.unparsed("mode", profiles.economyMode()));

        org.bukkit.entity.Player target;
        if (targetName != null) {
            target = org.bukkit.Bukkit.getPlayerExact(targetName);
            if (target == null) {
                messages.send(sender, "core.player-not-found");
                return Cmds.OK;
            }
        } else if (sender instanceof org.bukkit.entity.Player self) {
            target = self;
        } else {
            return Cmds.OK;
        }
        String name = target.getName();
        java.util.UUID uuid = target.getUniqueId();
        profiles.refresh(uuid).whenComplete((active, error) -> deliver(sender, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().warn("Reading the active profile of {} failed", name, error);
                messages.send(sender, "core.profile.failed", Msg.unparsed("player", name));
            } else if (active.isEmpty()) {
                messages.send(sender, "core.profile.none", Msg.unparsed("player", name));
            } else {
                var info = active.get();
                messages.send(sender, "core.profile.active", Msg.unparsed("player", name),
                        Msg.unparsed("profile", info.name()), Msg.unparsed("type", info.type()),
                        Msg.unparsed("role", info.role()), Msg.unparsed("status", info.status()),
                        Msg.unparsed("id", info.id().toString()));
            }
        }));
        return Cmds.OK;
    }

    /** Runs on the sender's own thread: the player's entity scheduler, or inline for the console. */
    private void deliver(org.bukkit.command.CommandSender sender, Runnable action) {
        if (sender instanceof org.bukkit.entity.Player player) {
            plugin.scheduler().entity(player, action, null);
        } else {
            action.run();
        }
    }

    private int version(CommandContext<CommandSourceStack> ctx) {
        messages.send(ctx.getSource().getSender(), "core.version",
                Msg.unparsed("version", plugin.getPluginMeta().getVersion()),
                Msg.number("modules", plugin.enabledModules().size()));
        return Cmds.OK;
    }

    private int modules(CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        messages.send(sender, "core.modules-header");
        for (Map.Entry<String, ModuleManager.State> entry : new java.util.TreeMap<>(plugin.moduleManager().states()).entrySet()) {
            messages.send(sender, "core.modules-entry." + entry.getValue().name().toLowerCase(java.util.Locale.ROOT),
                    Msg.unparsed("module", entry.getKey()));
        }
        return Cmds.OK;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        try {
            List<String> failed = plugin.reloadEverything();
            if (failed.isEmpty()) {
                messages.send(sender, "core.reloaded");
            } else {
                messages.send(sender, "core.reload-partial", Msg.unparsed("modules", String.join(", ", failed)));
            }
        } catch (Exception e) {
            messages.send(sender, "core.reload-failed", Msg.unparsed("error", String.valueOf(e.getMessage())));
        }
        return Cmds.OK;
    }

    /**
     * Writes docs/COMMANDS.md and docs/PERMISSIONS.md under the plugin folder. The command tree and the
     * permission registry are read on the calling thread; only the file write runs on the I/O executor.
     */
    private int docs(CommandContext<CommandSourceStack> ctx) {
        var sender = ctx.getSource().getSender();
        String version = plugin.getPluginMeta().getVersion();
        var service = plugin.commandService();
        java.util.Set<String> disabled = service.disabled();
        List<DocsGenerator.CommandDoc> commands = new java.util.ArrayList<>();
        for (var entry : service.entries()) {
            var spec = entry.spec();
            commands.add(new DocsGenerator.CommandDoc(entry.owner(), spec.name(), spec.aliases(), spec.description(),
                    DocsGenerator.usages(spec.node()), DocsGenerator.isDisabled(spec.name(), disabled)));
        }
        var manager = org.bukkit.Bukkit.getPluginManager();
        java.util.SortedMap<String, List<DocsGenerator.PermissionDoc>> permissions = new java.util.TreeMap<>();
        plugin.moduleManager().permissionsByModule().forEach((module, nodes) -> {
            List<DocsGenerator.PermissionDoc> docs = new java.util.ArrayList<>();
            for (String node : nodes) {
                org.bukkit.permissions.Permission permission = manager.getPermission(node);
                if (permission != null) {
                    docs.add(new DocsGenerator.PermissionDoc(node, permission.getDefault().name(), permission.getDescription()));
                }
            }
            permissions.put(module, docs);
        });
        java.nio.file.Path dir = plugin.getDataPath().resolve("docs");
        String commandsMd = DocsGenerator.commandsMarkdown(version, commands);
        String permissionsMd = DocsGenerator.permissionsMarkdown(version, permissions);
        plugin.scheduler().supplyIo(() -> {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.writeString(dir.resolve("COMMANDS.md"), commandsMd);
            java.nio.file.Files.writeString(dir.resolve("PERMISSIONS.md"), permissionsMd);
            return null;
        }).whenComplete((ignored, error) -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("Writing the command/permission docs to {} failed", dir, error);
                messages.send(sender, "core.docs-failed");
            } else {
                messages.send(sender, "core.docs-written", Msg.unparsed("path", dir.toString()),
                        Msg.number("commands", commands.size()),
                        Msg.number("permissions", permissions.values().stream().mapToInt(List::size).sum()));
            }
        });
        return Cmds.OK;
    }
}
