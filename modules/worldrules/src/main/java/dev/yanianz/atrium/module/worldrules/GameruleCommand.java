package dev.yanianz.atrium.module.worldrules;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/** Typed, tab-completed wrapper around Paper's game rule API. All world access runs on the global thread. */
final class GameruleCommand {
    private static final String PERMISSION = "atrium.gamerule";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final GameRuleCatalog catalog;

    GameruleCommand(ModuleContext ctx) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.catalog = new GameRuleCatalog(ctx.logger());
    }

    void register() {
        SuggestionProvider<CommandSourceStack> worlds = Cmds.suggest(
                () -> Bukkit.getWorlds().stream().map(World::getName).toList());
        SuggestionProvider<CommandSourceStack> rules = (c, builder) -> {
            for (String name : catalog.suggestions(builder.getRemainingLowerCase())) {
                builder.suggest(name);
            }
            return builder.buildFuture();
        };
        SuggestionProvider<CommandSourceStack> values = this::values;
        ctx.command(CommandSpec.of(Cmds.literal("gamerule", PERMISSION)
                .executes(c -> usage(c.getSource().getSender()))
                .then(Commands.argument("world", StringArgumentType.word()).suggests(worlds)
                        .executes(c -> usage(c.getSource().getSender()))
                        .then(Commands.argument("rule", StringArgumentType.word()).suggests(rules)
                                .executes(this::show)
                                .then(Commands.argument("value", StringArgumentType.word()).suggests(values)
                                        .executes(this::set))))
                .build(), "Read or change a world's game rules"));
    }

    private int usage(CommandSender sender) {
        Reply.send(ctx, sender, "worldrules.gamerule-usage");
        return Cmds.OK;
    }

    private java.util.concurrent.CompletableFuture<Suggestions> values(
            CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        GameRuleCatalog.Entry entry = catalog.find(StringArgumentType.getString(c, "rule"));
        if (entry == null) {
            return builder.buildFuture();
        }
        String remaining = builder.getRemainingLowerCase();
        switch (GameRuleValues.kindOf(entry.rule().getType())) {
            case BOOLEAN -> {
                for (String option : List.of("true", "false")) {
                    if (option.startsWith(remaining)) {
                        builder.suggest(option);
                    }
                }
            }
            case INTEGER -> builder.suggest(String.valueOf(entry.rule().getDefaultValue()));
            case UNSUPPORTED -> {
                // no sensible suggestion
            }
        }
        return builder.buildFuture();
    }

    private int show(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        World world = world(c);
        GameRuleCatalog.Entry entry = entry(c);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().global(() -> {
            Object value;
            try {
                value = GameRuleCatalog.value(world, entry.rule());
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot read game rule {} in {}", entry.name(), world.getName(), e);
                Reply.send(ctx, sender, "worldrules.gamerule-failed");
                return;
            }
            Reply.send(ctx, sender, "worldrules.gamerule-show",
                    Msg.unparsed("world", world.getName()),
                    Msg.unparsed("rule", entry.name()),
                    Msg.unparsed("value", GameRuleValues.describe(value)));
        });
        return Cmds.OK;
    }

    private int set(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        World world = world(c);
        GameRuleCatalog.Entry entry = entry(c);
        String raw = StringArgumentType.getString(c, "value");
        Object value;
        try {
            value = GameRuleValues.parse(entry.rule().getType(), raw);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "worldrules.gamerule-invalid",
                    Msg.unparsed("rule", entry.name()),
                    Msg.unparsed("value", raw),
                    Msg.unparsed("expected", expected(entry.rule().getType())));
        }
        CommandSender sender = c.getSource().getSender();
        String text = GameRuleValues.describe(value);
        ctx.scheduler().global(() -> {
            boolean changed;
            try {
                changed = GameRuleCatalog.set(world, entry.rule(), value);
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot set game rule {} in {} to {}", entry.name(), world.getName(), text, e);
                Reply.send(ctx, sender, "worldrules.gamerule-failed");
                return;
            }
            if (!changed) {
                Reply.send(ctx, sender, "worldrules.gamerule-failed");
                return;
            }
            Reply.send(ctx, sender, "worldrules.gamerule-set",
                    Msg.unparsed("world", world.getName()),
                    Msg.unparsed("rule", entry.name()),
                    Msg.unparsed("value", text));
        });
        return Cmds.OK;
    }

    private static String expected(Class<?> type) {
        return switch (GameRuleValues.kindOf(type)) {
            case BOOLEAN -> "true or false";
            case INTEGER -> "a whole number";
            case UNSUPPORTED -> "a supported value";
        };
    }

    private World world(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "world");
        World world = Bukkit.getWorld(name);
        if (world == null) {
            throw cmds.fail(c.getSource(), "worldrules.gamerule-unknown-world", Msg.unparsed("world", name));
        }
        return world;
    }

    private GameRuleCatalog.Entry entry(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "rule");
        GameRuleCatalog.Entry entry = catalog.find(name);
        if (entry == null) {
            throw cmds.fail(c.getSource(), "worldrules.gamerule-unknown-rule", Msg.unparsed("rule", name));
        }
        return entry;
    }
}
