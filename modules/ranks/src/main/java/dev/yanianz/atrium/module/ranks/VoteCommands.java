package dev.yanianz.atrium.module.ranks;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
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
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /votetop} and {@code /voteedit}: stored vote counts, independent of the Votifier hook. */
final class VoteCommands {
    static final String PERM_VOTETOP = "atrium.votetop";
    static final String PERM_VOTEEDIT = "atrium.voteedit";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final VoteStore store;
    private final ConfigHandle<RanksConfig> config;

    VoteCommands(ModuleContext ctx, VoteStore store, ConfigHandle<RanksConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.store = store;
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("votetop", PERM_VOTETOP)
                .executes(c -> top(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(c -> top(c, IntegerArgumentType.getInteger(c, "page"))))
                .build(), "Show the vote leaderboard"));

        ctx.command(CommandSpec.of(Cmds.literal("voteedit", PERM_VOTEEDIT)
                .executes(this::usage)
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .then(Commands.argument("action", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> List.of("set", "add", "take", "clear", "info")))
                                .executes(c -> edit(c, null))
                                .then(Commands.argument("amount", LongArgumentType.longArg(0))
                                        .executes(c -> edit(c, LongArgumentType.getLong(c, "amount"))))))
                .build(), "Set, add, take or inspect stored vote counts"));
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "ranks.voteedit-usage");
        return Cmds.OK;
    }

    // ---- /votetop ----

    private int top(CommandContext<CommandSourceStack> c, int requestedPage) {
        CommandSender sender = c.getSource().getSender();
        int pageSize = config.get().voteTopPageSize;
        store.count().thenCompose(total -> {
            int pages = VoteMath.pages(total, pageSize);
            int page = VoteMath.clampPage(requestedPage, pages);
            return store.top(VoteMath.offset(page, pageSize), pageSize)
                    .thenAccept(entries -> onSender(sender, () -> {
                        if (total == 0) {
                            ctx.messages().send(sender, "ranks.votetop-empty");
                            return;
                        }
                        ctx.messages().send(sender, "ranks.votetop-header",
                                Msg.number("page", page), Msg.number("pages", pages), Msg.number("total", total));
                        int rank = VoteMath.offset(page, pageSize) + 1;
                        for (VoteStore.Entry entry : entries) {
                            ctx.messages().send(sender, "ranks.votetop-entry",
                                    Msg.number("rank", rank++), Msg.unparsed("player", entry.name()),
                                    Msg.number("votes", entry.votes()));
                        }
                    }));
        }).whenComplete((value, error) -> {
            if (error != null) {
                failed(sender, "Reading the vote leaderboard", error);
            }
        });
        return Cmds.OK;
    }

    // ---- /voteedit ----

    private int edit(CommandContext<CommandSourceStack> c, Long amount) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String rawName = StringArgumentType.getString(c, "player");
        if (!VoteMath.validName(rawName)) {
            throw cmds.fail(c.getSource(), "ranks.voteedit-invalid-name");
        }
        String name = rawName.strip();
        String action = StringArgumentType.getString(c, "action").toLowerCase(Locale.ROOT);
        switch (action) {
            case "info" -> changeTip(sender, name);
            case "clear" -> store.clear(name).whenComplete((removed, error) -> {
                if (error != null) {
                    failed(sender, "Clearing the votes of " + name, error);
                    return;
                }
                onSender(sender, () -> ctx.messages().send(sender, "ranks.voteedit-cleared",
                        Msg.unparsed("player", name), Msg.number("removed", removed == null ? 0 : removed)));
            });
            case "set", "add", "take" -> {
                if (amount == null) {
                    throw cmds.fail(c.getSource(), "ranks.voteedit-usage");
                }
                apply(sender, name, action, amount);
            }
            default -> throw cmds.fail(c.getSource(), "ranks.voteedit-unknown-action",
                    Msg.unparsed("action", action));
        }
        return Cmds.OK;
    }

    private void changeTip(CommandSender sender, String name) {
        store.get(name).whenComplete((entry, error) -> {
            if (error != null) {
                failed(sender, "Reading the votes of " + name, error);
                return;
            }
            onSender(sender, () -> {
                if (entry.isEmpty()) {
                    ctx.messages().send(sender, "ranks.voteedit-info-none", Msg.unparsed("player", name));
                } else {
                    String last = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm",
                                    java.util.Locale.ROOT)
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(java.time.Instant.ofEpochMilli(entry.get().last()));
                    ctx.messages().send(sender, "ranks.voteedit-info",
                            Msg.unparsed("player", entry.get().name()),
                            Msg.number("votes", entry.get().votes()),
                            Msg.unparsed("last", last));
                }
            });
        });
    }

    private void apply(CommandSender sender, String name, String action, long amount) {
        java.util.concurrent.CompletableFuture<?> change = switch (action) {
            case "set" -> store.set(name, amount);
            case "add" -> store.change(name, amount);
            case "take" -> store.change(name, -amount);
            default -> throw new IllegalStateException("Checked action: " + action);
        };
        change.thenCompose(ignored -> store.get(name)).whenComplete((entry, error) -> {
            if (error != null) {
                failed(sender, "Editing the votes of " + name, error);
                return;
            }
            long votes = entry.map(VoteStore.Entry::votes).orElse(0L);
            onSender(sender, () -> ctx.messages().send(sender, "ranks.voteedit-done",
                    Msg.unparsed("player", name), Msg.number("votes", votes)));
        });
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private void failed(CommandSender sender, String action, Throwable error) {
        ctx.logger().warn("{} failed for {}", action, sender.getName(), error);
        onSender(sender, () -> ctx.messages().send(sender, "core.error"));
    }
}
