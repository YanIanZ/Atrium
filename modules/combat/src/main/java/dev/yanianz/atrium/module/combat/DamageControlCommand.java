package dev.yanianz.atrium.module.combat;

import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import org.bukkit.command.CommandSender;

/** {@code /damagecontrol [list]}: read-only view of the configured damage rules. */
final class DamageControlCommand {
    static final String PERMISSION = "atrium.damagecontrol";

    private final ModuleContext ctx;
    private final ConfigHandle<CombatConfig> config;

    DamageControlCommand(ModuleContext ctx, ConfigHandle<CombatConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("damagecontrol", PERMISSION)
                .executes(this::list)
                .then(Commands.literal("list").executes(this::list))
                .build(), "List the damage-control multipliers"));
    }

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        CombatConfig.DamageControl control = config.get().damageControl;
        if (!control.enabled) {
            ctx.messages().send(sender, "combat.damagecontrol-disabled");
            return Cmds.OK;
        }
        List<CombatConfig.DamageRule> rules = control.rules;
        if (rules == null || rules.isEmpty()) {
            ctx.messages().send(sender, "combat.damagecontrol-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "combat.damagecontrol-header", Msg.number("count", rules.size()));
        for (CombatConfig.DamageRule rule : rules) {
            if (rule == null) {
                continue;
            }
            ctx.messages().send(sender, "combat.damagecontrol-rule",
                    Msg.unparsed("cause", DamageControlRules.matchesAll(rule.cause) ? "*" : rule.cause),
                    Msg.number("multiplier", DamageControlRules.clamp(rule.multiplier)),
                    Msg.unparsed("permissions", join(rule.permissions)),
                    Msg.unparsed("worlds", join(rule.worlds)));
        }
        return Cmds.OK;
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "*";
        }
        return String.join(", ", values.stream().filter(value -> value != null && !value.isBlank()).toList());
    }
}
