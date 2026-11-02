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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Command tree of the performance module. */
final class PerformanceCommands {
    private static final int HISTORY_PAGE_SIZE = 10;
    private static final DateTimeFormatter HISTORY_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<PerformanceConfig> config;
    private final GroundCleanManager groundClean;
    private final LagReporter lag;
    private final GroundCleanStore store;

    PerformanceCommands(ModuleContext ctx, ConfigHandle<PerformanceConfig> config, GroundCleanManager groundClean,
            LagReporter lag, GroundCleanStore store) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.groundClean = groundClean;
        this.lag = lag;
        this.store = store;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("lag", "atrium.lag")
                .executes(c -> lag.report(c.getSource().getSender()))
                .build(), "Show TPS, tick time, memory and loaded entity counts"));

        ctx.command(CommandSpec.of(Cmds.literal("groundclean", "atrium.groundclean")
                .executes(c -> start(c, ""))
                .then(Cmds.literal("cancel").executes(this::cancel))
                .then(Cmds.literal("history", "atrium.groundclean.history")
                        .executes(c -> history(c, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
                                .executes(c -> history(c, IntegerArgumentType.getInteger(c, "page")))))
                .then(Commands.argument("options", StringArgumentType.greedyString())
                        .suggests(suggestOptions())
                        .executes(c -> start(c, StringArgumentType.getString(c, "options"))))
                .build(), "Remove old dropped items and XP orbs around online players"));
    }

    private int start(CommandContext<CommandSourceStack> c, String raw) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        GroundCleanOptions options;
        try {
            options = GroundCleanOptions.parse(raw);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "performance.groundclean-usage");
        }
        if (options.silent() && !sender.hasPermission("atrium.groundclean.silent")) {
            throw cmds.fail(c.getSource(), "performance.groundclean-silent-denied");
        }
        int leadSeconds = options.immediate() ? 0 : config.get().groundClean.manualCountdownSeconds();
        String world = options.world().isBlank() ? null : options.world();
        GroundCleanManager.StartResult result = groundClean.start(sender, world, options.silent(), leadSeconds,
                GroundCleanManager.Trigger.COMMAND);
        switch (result) {
            case BUSY -> throw cmds.fail(c.getSource(), "performance.groundclean-busy");
            case UNKNOWN_WORLD -> throw cmds.fail(c.getSource(), "performance.groundclean-unknown-world",
                    Msg.unparsed("world", world == null ? "?" : world));
            case NO_PLAYERS -> throw cmds.fail(c.getSource(), "performance.groundclean-no-players");
            case STARTED -> {
                // The started message is sent by the manager, after it registered the run.
            }
        }
        return Cmds.OK;
    }

    private int cancel(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        if (!groundClean.cancel()) {
            ctx.messages().send(sender, "performance.groundclean-cancel-none");
        }
        return Cmds.OK;
    }

    private int history(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = c.getSource().getSender();
        int offset = (page - 1) * HISTORY_PAGE_SIZE;
        store.recent(HISTORY_PAGE_SIZE, offset).whenComplete((runs, error) -> {
            if (error != null) {
                ctx.logger().error("Cannot read the ground cleanup history", error);
                deliver(sender, () -> ctx.messages().send(sender, "performance.groundclean-history-failed"));
                return;
            }
            deliver(sender, () -> sendHistory(sender, page, runs));
        });
        return Cmds.OK;
    }

    private void sendHistory(CommandSender sender, int page, List<GroundCleanStore.RunRecord> runs) {
        if (runs.isEmpty()) {
            ctx.messages().send(sender, "performance.groundclean-history-empty",
                    Msg.number("page", page));
            return;
        }
        ctx.messages().send(sender, "performance.groundclean-history-header",
                Msg.number("page", page), Msg.number("count", runs.size()));
        for (GroundCleanStore.RunRecord run : runs) {
            ctx.messages().send(sender, "performance.groundclean-history-line",
                    Msg.unparsed("time", HISTORY_TIME.format(Instant.ofEpochMilli(run.ranAt()))),
                    Msg.unparsed("world", run.world()),
                    Msg.number("items", run.items()),
                    Msg.number("orbs", run.orbs()),
                    Msg.number("protected", run.protectedCount()),
                    Msg.number("whitelisted", run.whitelisted()),
                    Msg.unparsed("seconds", String.format(Locale.ROOT, "%.1f", run.durationMs() / 1000.0)),
                    Msg.unparsed("actor", run.actor()),
                    Msg.unparsed("trigger", run.trigger().toLowerCase(Locale.ROOT)));
        }
        if (runs.size() == HISTORY_PAGE_SIZE) {
            ctx.messages().send(sender, "performance.groundclean-history-next", Msg.number("next", page + 1));
        }
    }

    private SuggestionProvider<CommandSourceStack> suggestOptions() {
        return (c, builder) -> {
            String remaining = builder.getRemaining();
            int space = remaining.lastIndexOf(' ');
            String prefix = space >= 0 ? remaining.substring(0, space + 1) : "";
            String last = remaining.substring(space + 1).toLowerCase(Locale.ROOT);
            if (last.startsWith("world:")) {
                String name = last.substring("world:".length());
                for (World world : Bukkit.getWorlds()) {
                    if (world.getName().toLowerCase(Locale.ROOT).startsWith(name)) {
                        builder.suggest(prefix + "world:" + world.getName());
                    }
                }
                return builder.buildFuture();
            }
            if (last.startsWith("-")) {
                for (String flag : List.of("-s", "-now")) {
                    if (flag.startsWith(last)) {
                        builder.suggest(prefix + flag);
                    }
                }
                return builder.buildFuture();
            }
            for (String flag : List.of("-s", "-now")) {
                if (flag.startsWith(last)) {
                    builder.suggest(prefix + flag);
                }
            }
            for (World world : Bukkit.getWorlds()) {
                if (world.getName().toLowerCase(Locale.ROOT).startsWith(last)) {
                    builder.suggest(prefix + world.getName());
                }
            }
            return builder.buildFuture();
        };
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
