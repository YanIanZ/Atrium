package dev.yanianz.atrium.module.worldrules;

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
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.LocalTime;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /ptime <time|freeze|unfreeze|reset> [player]}: per-player time, persisted across logins. */
final class PTimeCommand {
    static final String PERMISSION = "atrium.ptime";
    static final String OTHERS = "atrium.ptime.others";

    private static final List<String> OPTIONS = List.of(
            "day", "noon", "dusk", "night", "midnight", "dawn", "freeze", "unfreeze", "reset", "realtime");

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final PlayerTimeManager times;

    PTimeCommand(ModuleContext ctx, PlayerTimeManager times) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.times = times;
    }

    void register() {
        SuggestionProvider<CommandSourceStack> options = Cmds.suggest(() -> OPTIONS);
        ctx.command(CommandSpec.of(Cmds.literal("ptime", PERMISSION)
                .executes(c -> usage(c.getSource().getSender()))
                .then(Commands.argument("time", StringArgumentType.word()).suggests(options)
                        .executes(this::apply)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::apply)))
                .build(), "Show a fixed or moving time for one player"));
    }

    private int usage(CommandSender sender) {
        Reply.send(ctx, sender, "worldrules.ptime-usage");
        return Cmds.OK;
    }

    private int apply(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String raw = StringArgumentType.getString(c, "time");
        PlayerTimeSpec.Spec spec = PlayerTimeSpec.parse(raw, LocalTime.now());
        if (spec == null) {
            throw cmds.fail(c.getSource(), "worldrules.ptime-invalid", Msg.unparsed("input", raw));
        }
        Player target = cmds.selfOrOther(c, "target", OTHERS);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            if (!target.isOnline()) {
                Reply.send(ctx, sender, "worldrules.target-left");
                return;
            }
            switch (spec.action()) {
                case SET -> times.set(target, spec.ticks() == null ? 0 : spec.ticks());
                case FREEZE -> times.freeze(target, spec.ticks());
                case UNFREEZE -> times.unfreeze(target);
                case RESET -> times.reset(target);
            }
            String key = switch (spec.action()) {
                case SET -> "worldrules.ptime-set";
                case FREEZE -> "worldrules.ptime-frozen";
                case UNFREEZE -> "worldrules.ptime-unfrozen";
                case RESET -> "worldrules.ptime-reset";
            };
            String describe = PlayerTimeSpec.describe(target.getPlayerTime());
            ctx.messages().send(target, key, Msg.unparsed("time", describe));
            if (!target.equals(sender)) {
                Reply.send(ctx, sender, key + "-other",
                        Msg.unparsed("holder", target.getName()), Msg.unparsed("time", describe));
            }
        }, () -> Reply.send(ctx, sender, "worldrules.target-left"));
        return Cmds.OK;
    }
}
