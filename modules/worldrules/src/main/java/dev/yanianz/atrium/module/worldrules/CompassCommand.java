package dev.yanianz.atrium.module.worldrules;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /compass <player|spawn|x z|reset> [target]}. Everything touching a player runs on their thread. */
final class CompassCommand {
    private static final String PERMISSION = "atrium.compass";
    private static final String OTHERS = "atrium.compass.others";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final CompassManager manager;

    CompassCommand(ModuleContext ctx, CompassManager manager) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.manager = manager;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("compass", PERMISSION)
                .executes(c -> usage(c.getSource().getSender()))
                .then(Cmds.literal("spawn")
                        .executes(c -> spawn(c, false))
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(c -> spawn(c, false))))
                .then(Cmds.literal("reset")
                        .executes(c -> spawn(c, true))
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(c -> spawn(c, true))))
                .then(Commands.argument("player", ArgumentTypes.player())
                        .executes(this::track)
                        .then(Commands.argument("target", ArgumentTypes.player()).executes(this::track)))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(this::coordinates)
                                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::coordinates))))
                .build(), "Point a compass at a player, the world spawn or coordinates"));
    }

    private int usage(CommandSender sender) {
        Reply.send(ctx, sender, "worldrules.compass-usage");
        return Cmds.OK;
    }

    private int track(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player tracked = cmds.argPlayer(c, "player");
        Player holder = cmds.selfOrOther(c, "target", OTHERS);
        CommandSender sender = c.getSource().getSender();
        String trackedName = tracked.getName();
        ctx.scheduler().entity(holder, () -> {
            if (!holder.isOnline()) {
                Reply.send(ctx, sender, "worldrules.target-left");
                return;
            }
            CompassManager.Start result = manager.start(holder, tracked);
            switch (result) {
                case STARTED -> {
                    Reply.send(ctx, holder, "worldrules.compass-tracking",
                            Msg.unparsed("player", trackedName));
                    if (!holder.equals(sender)) {
                        Reply.send(ctx, sender, "worldrules.compass-tracking-other",
                                Msg.unparsed("player", trackedName),
                                Msg.unparsed("holder", holder.getName()));
                    }
                }
                case ALREADY -> Reply.send(ctx, holder, "worldrules.compass-already",
                        Msg.unparsed("player", trackedName));
                case BUSY -> Reply.send(ctx, holder, "worldrules.compass-busy");
                case NO_COMPASS -> Reply.send(ctx, holder, "worldrules.compass-needs-item");
            }
        }, () -> Reply.send(ctx, sender, "worldrules.target-left"));
        return Cmds.OK;
    }

    private int spawn(CommandContext<CommandSourceStack> c, boolean reset) throws CommandSyntaxException {
        Player holder = cmds.selfOrOther(c, "target", OTHERS);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(holder, () -> {
            if (!holder.isOnline()) {
                Reply.send(ctx, sender, "worldrules.target-left");
                return;
            }
            World world = holder.getWorld();
            Location spawn = world.getSpawnLocation();
            manager.setTarget(holder, spawn);
            String key = reset ? "worldrules.compass-reset" : "worldrules.compass-spawn";
            Reply.send(ctx, holder, key, Msg.unparsed("world", world.getName()));
            if (!holder.equals(sender)) {
                Reply.send(ctx, sender, key + "-other",
                        Msg.unparsed("world", world.getName()),
                        Msg.unparsed("holder", holder.getName()));
            }
        }, () -> Reply.send(ctx, sender, "worldrules.target-left"));
        return Cmds.OK;
    }

    private int coordinates(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        int x = IntegerArgumentType.getInteger(c, "x");
        int z = IntegerArgumentType.getInteger(c, "z");
        Player holder = cmds.selfOrOther(c, "target", OTHERS);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().entity(holder, () -> {
            if (!holder.isOnline()) {
                Reply.send(ctx, sender, "worldrules.target-left");
                return;
            }
            World world = holder.getWorld();
            // The compass only uses x/z. Y is a stable value inside this world's height range.
            double y = Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, 64));
            manager.setTarget(holder, new Location(world, x + 0.5, y, z + 0.5));
            Reply.send(ctx, holder, "worldrules.compass-coords",
                    Msg.number("x", x), Msg.number("z", z), Msg.unparsed("world", world.getName()));
            if (!holder.equals(sender)) {
                Reply.send(ctx, sender, "worldrules.compass-coords-other",
                        Msg.number("x", x), Msg.number("z", z), Msg.unparsed("world", world.getName()),
                        Msg.unparsed("holder", holder.getName()));
            }
        }, () -> Reply.send(ctx, sender, "worldrules.target-left"));
        return Cmds.OK;
    }
}
