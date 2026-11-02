package dev.yanianz.atrium.module.worldrules;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

/** {@code /weather <world|all> <clear|rain|storm> [seconds]}: world weather with an optional duration. */
final class WeatherCommand {
    static final String PERMISSION = "atrium.weather";

    private final ModuleContext ctx;

    WeatherCommand(ModuleContext ctx) {
        this.ctx = ctx;
    }

    void register() {
        SuggestionProvider<CommandSourceStack> worlds = Cmds.suggest(() -> {
            List<String> names = new ArrayList<>();
            for (World world : Bukkit.getWorlds()) {
                names.add(world.getName());
            }
            names.add("all");
            return names;
        });
        ctx.command(CommandSpec.of(Cmds.literal("weather", PERMISSION)
                .executes(c -> usage(c.getSource().getSender()))
                .then(Commands.argument("world", StringArgumentType.word()).suggests(worlds)
                        .executes(c -> usage(c.getSource().getSender()))
                        .then(kind("clear", WeatherKind.CLEAR))
                        .then(kind("rain", WeatherKind.RAIN))
                        .then(kind("storm", WeatherKind.STORM)))
                .build(), "Change the weather of a world or of every world"));
    }

    private com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> kind(
            String name, WeatherKind kind) {
        return Commands.literal(name)
                .executes(c -> apply(c, kind, null))
                .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 86400))
                        .executes(c -> apply(c, kind, IntegerArgumentType.getInteger(c, "seconds"))));
    }

    private int usage(CommandSender sender) {
        Reply.send(ctx, sender, "worldrules.weather-usage");
        return Cmds.OK;
    }

    private int apply(CommandContext<CommandSourceStack> c, WeatherKind kind, Integer seconds)
            throws CommandSyntaxException {
        String name = StringArgumentType.getString(c, "world");
        List<World> targets = new ArrayList<>();
        if (name.equalsIgnoreCase("all")) {
            targets.addAll(Bukkit.getWorlds());
        } else {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                throw cmdsFail(c, name);
            }
            targets.add(world);
        }
        if (targets.isEmpty()) {
            throw cmdsFail(c, name);
        }
        CommandSender sender = c.getSource().getSender();
        int ticks = seconds == null ? -1 : seconds * 20;
        ctx.scheduler().global(() -> {
            for (World world : targets) {
                world.setStorm(kind.storm());
                world.setThundering(kind.thundering());
                if (ticks >= 0) {
                    if (kind.thundering()) {
                        world.setThunderDuration(ticks);
                    }
                    if (kind.storm()) {
                        world.setWeatherDuration(ticks);
                    } else {
                        world.setClearWeatherDuration(ticks);
                    }
                }
            }
            if (targets.size() == 1) {
                Reply.send(ctx, sender, "worldrules.weather-set",
                        Msg.unparsed("world", targets.getFirst().getName()),
                        Msg.unparsed("type", kind.key()),
                        Msg.unparsed("duration", seconds == null ? "default" : seconds + "s"));
            } else {
                Reply.send(ctx, sender, "worldrules.weather-set-all",
                        Msg.unparsed("type", kind.key()),
                        Msg.unparsed("duration", seconds == null ? "default" : seconds + "s"));
            }
        });
        return Cmds.OK;
    }

    private CommandSyntaxException cmdsFail(CommandContext<CommandSourceStack> c, String world) {
        return ctx.commands().fail(c.getSource(), "worldrules.gamerule-unknown-world", Msg.unparsed("world", world));
    }
}
