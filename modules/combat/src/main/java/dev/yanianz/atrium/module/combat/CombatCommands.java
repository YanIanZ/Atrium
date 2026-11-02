package dev.yanianz.atrium.module.combat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /combat} and {@code /notarget}. */
final class CombatCommands {
    static final String STATUS = "atrium.combat";
    static final String STATUS_OTHERS = "atrium.combat.others";
    static final String ADMIN = "atrium.combat.admin";
    static final String NOTARGET = "atrium.notarget";
    static final String NOTARGET_OTHERS = "atrium.notarget.others";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<CombatConfig> config;
    private final CombatTracker tracker;
    private final NotargetManager notarget;

    CombatCommands(ModuleContext ctx, ConfigHandle<CombatConfig> config, CombatTracker tracker,
            NotargetManager notarget) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.tracker = tracker;
        this.notarget = notarget;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("combat", STATUS)
                .executes(this::selfStatus)
                .then(Commands.literal("status")
                        .executes(this::selfStatus)
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .requires(Cmds.permission(STATUS_OTHERS))
                                .executes(this::otherStatus)))
                .then(Commands.literal("tag")
                        .requires(Cmds.permission(ADMIN))
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .executes(c -> tag(c, config.get().tag.seconds()))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                                        .executes(c -> tag(c, IntegerArgumentType.getInteger(c, "seconds"))))))
                .then(Commands.literal("untag")
                        .requires(Cmds.permission(ADMIN))
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::untag)))
                .then(Commands.literal("help").executes(this::usage))
                .build(), "Show or change combat tags"));

        ctx.command(CommandSpec.of(Cmds.literal("notarget", NOTARGET)
                .executes(this::toggleSelf)
                .then(Commands.argument("target", ArgumentTypes.player())
                        .requires(Cmds.permission(NOTARGET_OTHERS))
                        .executes(this::toggleOther))
                .build(), "Make mobs ignore you"));
    }

    private int selfStatus(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        sendStatus(c.getSource().getSender(), player, false);
        return Cmds.OK;
    }

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "combat.command-usage");
        return Cmds.OK;
    }

    private int otherStatus(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.argPlayer(c, "target");
        sendStatus(c.getSource().getSender(), target, true);
        return Cmds.OK;
    }

    private void sendStatus(CommandSender sender, Player target, boolean other) {
        ctx.scheduler().entity(target, () -> {
            long remaining = tracker.remainingMillis(target.getUniqueId());
            int seconds = CombatTiming.secondsLeft(remaining);
            if (remaining > 0) {
                Reply.send(ctx, sender, other ? "combat.tag-status-other" : "combat.tag-status-self",
                        Msg.unparsed("player", target.getName()), Msg.number("seconds", seconds));
            } else {
                Reply.send(ctx, sender, other ? "combat.tag-status-none-other" : "combat.tag-status-none-self",
                        Msg.unparsed("player", target.getName()));
            }
        }, () -> Reply.send(ctx, sender, "combat.target-left", Msg.unparsed("player", target.getName())));
    }

    private int tag(CommandContext<CommandSourceStack> c, int seconds) throws CommandSyntaxException {
        Player target = cmds.argPlayer(c, "target");
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            if (!tracker.canTag(target)) {
                Reply.send(ctx, sender, "combat.tag-not-applied", Msg.unparsed("player", target.getName()));
                return;
            }
            tracker.tagNow(target, seconds * 1000L);
            Reply.send(ctx, sender, "combat.tagged-other",
                    Msg.unparsed("player", target.getName()), Msg.number("seconds", seconds));
        }, () -> Reply.send(ctx, sender, "combat.target-left", Msg.unparsed("player", target.getName())));
        return Cmds.OK;
    }

    private int untag(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.argPlayer(c, "target");
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(target, () -> {
            boolean wasTagged = tracker.clearNow(target);
            Reply.send(ctx, sender, wasTagged ? "combat.untagged-other" : "combat.untagged-none",
                    Msg.unparsed("player", target.getName()));
        }, () -> Reply.send(ctx, sender, "combat.target-left", Msg.unparsed("player", target.getName())));
        return Cmds.OK;
    }

    private int toggleSelf(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        toggle(c.getSource().getSender(), player);
        return Cmds.OK;
    }

    private int toggleOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.argPlayer(c, "target");
        toggle(c.getSource().getSender(), target);
        return Cmds.OK;
    }

    private void toggle(CommandSender sender, Player target) {
        if (!config.get().notarget.enabled) {
            Reply.send(ctx, sender, "combat.notarget-disabled");
            return;
        }
        ctx.scheduler().entity(target, () -> {
            boolean enabled = notarget.toggle(target);
            String key = enabled ? "combat.notarget-on" : "combat.notarget-off";
            ctx.messages().send(target, key);
            if (!target.equals(sender)) {
                Reply.send(ctx, sender, key + "-other", Msg.unparsed("player", target.getName()));
            }
        }, () -> Reply.send(ctx, sender, "combat.target-left", Msg.unparsed("player", target.getName())));
    }
}
