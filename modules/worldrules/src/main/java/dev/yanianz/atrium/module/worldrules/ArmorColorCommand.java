package dev.yanianz.atrium.module.worldrules;

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
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /armorcolor <on|off|status> [player]}: opt in to the animated leather armour. */
final class ArmorColorCommand {
    private static final List<String> OPTIONS = List.of("on", "off", "status");

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<WorldRulesConfig> config;
    private final LeatherAnimationManager animation;

    ArmorColorCommand(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config, LeatherAnimationManager animation) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.animation = animation;
    }

    void register() {
        SuggestionProvider<CommandSourceStack> states = Cmds.suggest(() -> OPTIONS);
        ctx.command(CommandSpec.of(Cmds.literal("armorcolor", LeatherAnimationManager.PERMISSION)
                .executes(c -> usage(c.getSource().getSender()))
                .then(Commands.argument("state", StringArgumentType.word()).suggests(states)
                        .executes(this::apply)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::apply)))
                .build(), "Opt in or out of the animated leather armour colours"));
    }

    private int usage(CommandSender sender) {
        Reply.send(ctx, sender, "worldrules.armorcolor-usage");
        return Cmds.OK;
    }

    private int apply(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String state = StringArgumentType.getString(c, "state").toLowerCase(Locale.ROOT);
        if (!OPTIONS.contains(state)) {
            throw cmds.fail(c.getSource(), "worldrules.armorcolor-usage");
        }
        Player target = cmds.selfOrOther(c, "target", LeatherAnimationManager.OTHERS);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            if (!target.isOnline()) {
                Reply.send(ctx, sender, "worldrules.target-left");
                return;
            }
            switch (state) {
                case "on" -> {
                    if (!config.get().leatherAnimation.enabled) {
                        Reply.send(ctx, target, "worldrules.armorcolor-unavailable");
                        return;
                    }
                    animation.optIn(target);
                    Reply.send(ctx, target, "worldrules.armorcolor-enabled");
                    if (!target.equals(sender)) {
                        Reply.send(ctx, sender, "worldrules.armorcolor-enabled-other",
                                Msg.unparsed("holder", target.getName()));
                    }
                }
                case "off" -> {
                    animation.optOut(target);
                    Reply.send(ctx, target, "worldrules.armorcolor-disabled");
                    if (!target.equals(sender)) {
                        Reply.send(ctx, sender, "worldrules.armorcolor-disabled-other",
                                Msg.unparsed("holder", target.getName()));
                    }
                }
                default -> {
                    boolean on = animation.optedIn(target);
                    Reply.send(ctx, target, on
                            ? "worldrules.armorcolor-status-on" : "worldrules.armorcolor-status-off");
                    if (!target.equals(sender)) {
                        Reply.send(ctx, sender, (on ? "worldrules.armorcolor-status-on-other"
                                        : "worldrules.armorcolor-status-off-other"),
                                Msg.unparsed("holder", target.getName()));
                    }
                }
            }
        }, () -> Reply.send(ctx, sender, "worldrules.target-left"));
        return Cmds.OK;
    }
}
