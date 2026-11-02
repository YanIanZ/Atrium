package dev.yanianz.atrium.module.performance;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Server-list and console commands of the performance module: {@code /maxplayers}, {@code /viewdistance},
 * {@code /updates} and {@code /consolefilter}. Every player-targeting change runs on the target's own thread and
 * world settings run on the global region thread, so the same code works on Paper and Folia.
 */
final class ServerCommands {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<PerformanceConfig> config;
    private final ServerListListener list;
    private final UpdateChecker updates;
    private final PluginErrorLog recent;
    private final Supplier<LogRules> rules;

    ServerCommands(ModuleContext ctx, ConfigHandle<PerformanceConfig> config, ServerListListener list,
            UpdateChecker updates, PluginErrorLog recent, Supplier<LogRules> rules) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.list = list;
        this.updates = updates;
        this.recent = recent;
        this.rules = rules;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("maxplayers", "atrium.maxplayers")
                .executes(this::maxShow)
                .then(Cmds.literal("reset").executes(this::maxReset))
                .then(Commands.argument("slots", IntegerArgumentType.integer(1, 999))
                        .executes(c -> maxSet(c, IntegerArgumentType.getInteger(c, "slots"))))
                .build(), "Show or change the player cap the server list reports"));

        ctx.command(CommandSpec.of(Cmds.literal("viewdistance", "atrium.viewdistance")
                .executes(this::distanceShow)
                .then(Cmds.literal("reset").executes(this::distanceReset))
                .then(Cmds.literal("world")
                        .then(Commands.argument("distance", IntegerArgumentType.integer(2, 32))
                                .executes(c -> worldDistance(c, null,
                                        IntegerArgumentType.getInteger(c, "distance")))
                                .then(Commands.argument("world", StringArgumentType.word()).suggests(worlds())
                                        .executes(c -> worldDistance(c, StringArgumentType.getString(c, "world"),
                                                IntegerArgumentType.getInteger(c, "distance"))))))
                .then(Cmds.literal("global", "atrium.viewdistance.global")
                        .then(Commands.argument("distance", IntegerArgumentType.integer(2, 32))
                                .executes(c -> globalDistance(c, IntegerArgumentType.getInteger(c, "distance")))))
                .then(Commands.argument("distance", IntegerArgumentType.integer(2, 32))
                        .executes(c -> playerDistance(c, IntegerArgumentType.getInteger(c, "distance"), null))
                        .then(Commands.argument("player", StringArgumentType.word()).suggests(Cmds.knownPlayers(ctx.users()))
                                .executes(c -> playerDistance(c, IntegerArgumentType.getInteger(c, "distance"),
                                        StringArgumentType.getString(c, "player")))))
                .build(), "Show or change the view distance of you, a world or every world"));

        ctx.command(CommandSpec.of(Cmds.literal("updates", "atrium.updates")
                .executes(this::updateStatus)
                .then(Cmds.literal("check").executes(this::updateCheck))
                .build(), "Show whether a newer Atrium release exists", "updatecheck"));

        ctx.command(CommandSpec.of(Cmds.literal("consolefilter", "atrium.consolefilter")
                .executes(this::filterStatus)
                .then(Cmds.literal("status").executes(this::filterStatus))
                .then(Cmds.literal("recent").executes(c -> filterRecent(c, 10))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(c -> filterRecent(c, IntegerArgumentType.getInteger(c, "count")))))
                .then(Cmds.literal("test")
                        .then(Commands.argument("line", StringArgumentType.greedyString())
                                .executes(c -> filterTest(c, StringArgumentType.getString(c, "line")))))
                .then(Cmds.literal("clear").executes(this::filterClear))
                .build(), "Inspect the console filter and the recent problems"));
    }

    // ---- /maxplayers ----

    private int maxShow(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ctx.messages().send(sender, "performance.maxplayers-current",
                Msg.number("reported", list.lastMax() > 0 ? list.lastMax() : Bukkit.getMaxPlayers()),
                Msg.number("real", Bukkit.getMaxPlayers()),
                Msg.unparsed("session", list.runtimeMax() > 0 ? Integer.toString(list.runtimeMax()) : "server"));
        return Cmds.OK;
    }

    private int maxSet(CommandContext<CommandSourceStack> c, int slots) {
        CommandSender sender = c.getSource().getSender();
        list.runtimeMax(slots);
        ctx.scheduler().global(() -> {
            try {
                Bukkit.setMaxPlayers(slots);
            } catch (RuntimeException e) {
                // The server-list override still reports the new cap; the server-wide setter is a bonus.
                ctx.logger().warn("The server refused setMaxPlayers({}); the server-list cap is still overridden",
                        slots, e);
            }
        });
        ctx.messages().send(sender, "performance.maxplayers-set", Msg.number("slots", slots));
        return Cmds.OK;
    }

    private int maxReset(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        list.runtimeMax(-1);
        ctx.messages().send(sender, "performance.maxplayers-reset");
        return Cmds.OK;
    }

    // ---- /viewdistance ----

    private int distanceShow(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        World world = player.getWorld();
        ctx.messages().send(player, "performance.viewdistance-current",
                Msg.number("player", player.getViewDistance()),
                Msg.number("client", player.getClientViewDistance()),
                Msg.number("world", world.getViewDistance()),
                Msg.unparsed("world_name", world.getName()),
                Msg.number("min", config.get().viewDistance.min()),
                Msg.number("max", config.get().viewDistance.max()));
        return Cmds.OK;
    }

    private int playerDistance(CommandContext<CommandSourceStack> c, int distance, String targetName)
            throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player target = targetName == null ? cmds.player(c.getSource()) : resolveOnline(c, targetName);
        if (!target.equals(sender) && !sender.hasPermission("atrium.viewdistance.others")) {
            throw cmds.fail(c.getSource(), "core.no-permission-others",
                    Msg.unparsed("permission", "atrium.viewdistance.others"));
        }
        int clamped = clamp(distance);
        ctx.scheduler().entity(target, () -> target.setViewDistance(clamped), null);
        ctx.messages().send(sender, "performance.viewdistance-set",
                Msg.unparsed("target", target.getName()), Msg.number("distance", clamped));
        return Cmds.OK;
    }

    private int worldDistance(CommandContext<CommandSourceStack> c, String worldName, int distance)
            throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        if (!sender.hasPermission("atrium.viewdistance.world")) {
            throw cmds.fail(c.getSource(), "core.no-permission-others",
                    Msg.unparsed("permission", "atrium.viewdistance.world"));
        }
        World world;
        if (worldName == null) {
            if (sender instanceof Player player) {
                world = player.getWorld();
            } else {
                throw cmds.fail(c.getSource(), "performance.viewdistance-world-required");
            }
        } else {
            world = Bukkit.getWorld(worldName);
            if (world == null) {
                throw cmds.fail(c.getSource(), "performance.viewdistance-unknown-world",
                        Msg.unparsed("world", worldName));
            }
        }
        int clamped = clamp(distance);
        ctx.scheduler().global(() -> world.setViewDistance(clamped));
        ctx.messages().send(sender, "performance.viewdistance-world-set",
                Msg.unparsed("world", world.getName()), Msg.number("distance", clamped));
        return Cmds.OK;
    }

    private int globalDistance(CommandContext<CommandSourceStack> c, int distance) {
        CommandSender sender = c.getSource().getSender();
        int clamped = clamp(distance);
        List<World> worlds = List.copyOf(Bukkit.getWorlds());
        ctx.scheduler().global(() -> {
            for (World world : worlds) {
                world.setViewDistance(clamped);
            }
        });
        ctx.messages().send(sender, "performance.viewdistance-global-set",
                Msg.number("distance", clamped), Msg.number("worlds", worlds.size()));
        return Cmds.OK;
    }

    private int distanceReset(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        ctx.scheduler().entity(player, () -> player.setViewDistance(-1), null);
        ctx.messages().send(player, "performance.viewdistance-reset");
        return Cmds.OK;
    }

    private int clamp(int requested) {
        PerformanceConfig.ViewDistance cfg = config.get().viewDistance;
        return Math.max(cfg.min(), Math.min(cfg.max(), requested));
    }

    private Player resolveOnline(CommandContext<CommandSourceStack> c, String name) throws CommandSyntaxException {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            throw cmds.fail(c.getSource(), "performance.viewdistance-unknown-player", Msg.unparsed("player", name));
        }
        return target;
    }

    private static SuggestionProvider<CommandSourceStack> worlds() {
        return Cmds.suggest(() -> Bukkit.getWorlds().stream().map(World::getName).toList());
    }

    // ---- /updates ----

    private int updateStatus(CommandContext<CommandSourceStack> c) {
        sendStatus(c.getSource().getSender());
        return Cmds.OK;
    }

    private int updateCheck(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (!config.get().updates.enabled) {
            ctx.messages().send(sender, "performance.update-disabled");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "performance.update-checking");
        updates.check().whenComplete((status, error) -> deliver(sender, () -> sendStatus(sender)));
        return Cmds.OK;
    }

    private void sendStatus(CommandSender sender) {
        UpdateChecker.Status status = updates.status();
        ctx.messages().send(sender, "performance.update-header", Msg.unparsed("current", updates.currentVersion()));
        if (!status.checked()) {
            ctx.messages().send(sender, "performance.update-never");
            return;
        }
        if (!status.error().isEmpty()) {
            ctx.messages().send(sender, "performance.update-failed", Msg.unparsed("reason", status.error()));
            return;
        }
        if (status.newer()) {
            ctx.messages().send(sender, "performance.update-notice",
                    Msg.unparsed("latest", status.latest()),
                    Msg.unparsed("current", updates.currentVersion()),
                    Msg.unparsed("url", status.url().isEmpty() ? "-" : status.url()),
                    Msg.link("release_link", status.url()));
        } else {
            ctx.messages().send(sender, "performance.update-latest", Msg.unparsed("current", updates.currentVersion()));
        }
    }

    // ---- /consolefilter ----

    private int filterStatus(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        LogRules current = rules.get();
        ctx.messages().send(sender, "performance.filter-status",
                Msg.unparsed("state", current.enabled() ? "on" : "off"),
                Msg.number("rules", current.ruleCount()),
                Msg.number("recent", recent.size()),
                Msg.unparsed("errors", current.hideErrors() ? "hidden" : "kept"));
        return Cmds.OK;
    }

    private int filterRecent(CommandContext<CommandSourceStack> c, int count) {
        CommandSender sender = c.getSource().getSender();
        List<PluginErrorLog.Entry> entries = recent.recent(count);
        if (entries.isEmpty()) {
            ctx.messages().send(sender, "performance.filter-recent-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "performance.filter-recent-header", Msg.number("count", entries.size()));
        for (PluginErrorLog.Entry entry : entries) {
            ctx.messages().send(sender, "performance.filter-recent-line",
                    Msg.unparsed("tag", entry.tag()),
                    Msg.unparsed("level", entry.level()),
                    Msg.unparsed("message", entry.message().length() > 300
                            ? entry.message().substring(0, 300) + "..." : entry.message()));
        }
        return Cmds.OK;
    }

    private int filterTest(CommandContext<CommandSourceStack> c, String line) {
        CommandSender sender = c.getSource().getSender();
        LogRules.Decision decision = rules.get().decide("", line, false);
        String result = decision.hide() ? "hide" : decision.tagged() ? "tag" : "pass";
        ctx.messages().send(sender, "performance.filter-test",
                Msg.unparsed("line", line), Msg.unparsed("result", result),
                Msg.unparsed("tag", decision.tag()));
        return Cmds.OK;
    }

    private int filterClear(CommandContext<CommandSourceStack> c) {
        recent.clear();
        ctx.messages().send(c.getSource().getSender(), "performance.filter-cleared");
        return Cmds.OK;
    }

    private void deliver(CommandSender sender, Runnable action) {
        if (sender instanceof Player player) {
            if (player.isOnline()) {
                ctx.scheduler().entity(player, () -> {
                    if (player.isOnline()) {
                        action.run();
                    }
                }, null);
            }
        } else {
            action.run();
        }
    }
}
