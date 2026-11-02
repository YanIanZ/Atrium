package dev.yanianz.atrium.module.worldrules;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.WeatherType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /pweather <clear|rain|reset> [player]}: per-player client weather. */
final class PWeatherCommand {
    static final String PERMISSION = "atrium.pweather";
    static final String OTHERS = "atrium.pweather.others";

    private final ModuleContext ctx;
    private final Cmds cmds;

    PWeatherCommand(ModuleContext ctx) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("pweather", PERMISSION)
                .executes(c -> usage(c.getSource().getSender()))
                .then(Cmds.literal("clear")
                        .executes(c -> apply(c, WeatherType.CLEAR))
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(c -> apply(c, WeatherType.CLEAR))))
                .then(Cmds.literal("rain")
                        .executes(c -> apply(c, WeatherType.DOWNFALL))
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(c -> apply(c, WeatherType.DOWNFALL))))
                .then(Cmds.literal("reset")
                        .executes(c -> apply(c, null))
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(c -> apply(c, null))))
                .build(), "Show clear or rainy weather to one player"));
    }

    private int usage(CommandSender sender) {
        Reply.send(ctx, sender, "worldrules.pweather-usage");
        return Cmds.OK;
    }

    private int apply(CommandContext<CommandSourceStack> c, WeatherType weather) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", OTHERS);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            if (!target.isOnline()) {
                Reply.send(ctx, sender, "worldrules.target-left");
                return;
            }
            String key;
            if (weather == null) {
                target.resetPlayerWeather();
                key = "worldrules.pweather-reset";
            } else {
                target.setPlayerWeather(weather);
                key = weather == WeatherType.CLEAR ? "worldrules.pweather-clear" : "worldrules.pweather-rain";
            }
            ctx.messages().send(target, key);
            if (!target.equals(sender)) {
                Reply.send(ctx, sender, key + "-other", Msg.unparsed("holder", target.getName()));
            }
        }, () -> Reply.send(ctx, sender, "worldrules.target-left"));
        return Cmds.OK;
    }
}
