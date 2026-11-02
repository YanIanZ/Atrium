package dev.yanianz.atrium.module.seating;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** /sit, /lay, /crawl, /ride and the small /seating admin command. */
final class SeatingCommands {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final SeatManager seats;
    private final CrawlManager crawl;
    private final RideManager ride;
    private final ConfigHandle<SeatingConfig> config;

    SeatingCommands(ModuleContext ctx, SeatManager seats, CrawlManager crawl, RideManager ride,
            ConfigHandle<SeatingConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.seats = seats;
        this.crawl = crawl;
        this.ride = ride;
        this.config = config;
    }

    void register() {
        seatCommand("sit", "Sit down, or stand up when already sitting",
                SeatingModule.PERM_SIT, SeatingModule.PERM_SIT_OTHERS, SeatIndex.Mode.SIT);
        seatCommand("lay", "Lay down on a lower seat, or stand up",
                SeatingModule.PERM_LAY, SeatingModule.PERM_LAY_OTHERS, SeatIndex.Mode.LAY);
        ctx.command(CommandSpec.of(Cmds.literal("crawl", SeatingModule.PERM_CRAWL)
                .executes(this::crawl)
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::crawl))
                .build(), "Crawl with the swimming pose, or stop crawling"));
        ctx.command(CommandSpec.of(Cmds.literal("ride", SeatingModule.PERM_RIDE)
                .executes(this::rideAimed)
                .then(Cmds.literal("stop")
                        .executes(this::rideStop)
                        .then(Commands.argument("target", ArgumentTypes.player())
                                .requires(Cmds.permission(SeatingModule.PERM_RIDE_OFF))
                                .executes(this::rideStopOther)))
                .then(Cmds.literal("launch", SeatingModule.PERM_LAUNCH)
                        .executes(this::launch)
                        .then(Commands.argument("power", DoubleArgumentType.doubleArg())
                                .suggests(Cmds.suggest(() -> java.util.List.of("0.5", "1.0", "1.5", "2.0")))
                                .executes(this::launch)))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .requires(Cmds.permission(SeatingModule.PERM_RIDE_PLAYERS))
                        .executes(this::ridePlayer))
                .build(), "Ride the entity you look at, another player, or stop riding",
                "riding"));
        ctx.command(CommandSpec.of(Cmds.literal("seating", SeatingModule.PERM_ADMIN)
                .executes(this::help)
                .then(Cmds.literal("help").executes(this::help))
                .then(Cmds.literal("sweep", SeatingModule.PERM_SWEEP).executes(this::sweep))
                .build(), "Show seating help or remove stale seat records"));
    }

    private void seatCommand(String name, String description, String permission, String othersPermission,
            SeatIndex.Mode mode) {
        ctx.command(CommandSpec.of(Cmds.literal(name, permission)
                .executes(c -> seat(c, mode, othersPermission))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .executes(c -> seat(c, mode, othersPermission)))
                .build(), description));
    }

    private int seat(CommandContext<CommandSourceStack> c, SeatIndex.Mode mode, String othersPermission)
            throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", othersPermission);
        CommandSender sender = c.getSource().getSender();
        onPlayer(target, sender, () -> seats.toggle(target, mode, sender));
        return Cmds.OK;
    }

    private int crawl(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", SeatingModule.PERM_CRAWL_OTHERS);
        CommandSender sender = c.getSource().getSender();
        onPlayer(target, sender, () -> {
            if (seats.isSeated(target)) {
                report(sender, "seating.crawl-seated");
                return;
            }
            crawl.toggle(target, sender);
        });
        return Cmds.OK;
    }

    // ---- /ride ----

    private int rideAimed(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        if (!configRideEnabled()) {
            ctx.messages().send(player, "seating.ride-disabled");
            return Cmds.OK;
        }
        if (player.getVehicle() != null) {
            dismount(player);
            return Cmds.OK;
        }
        Entity target = ride.aimed(player);
        if (target == null) {
            ctx.messages().send(player, "seating.ride-no-target");
            return Cmds.OK;
        }
        if (target instanceof Player && !player.hasPermission(SeatingModule.PERM_RIDE_PLAYERS)) {
            ctx.messages().send(player, "seating.no-permission");
            return Cmds.OK;
        }
        RideRules.Verdict verdict = ride.mount(player, target);
        ride.report(player, verdict, RideManager.describe(target));
        return Cmds.OK;
    }

    private int ridePlayer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        if (!configRideEnabled()) {
            ctx.messages().send(player, "seating.ride-disabled");
            return Cmds.OK;
        }
        Player target = cmds.argPlayer(c, "target");
        if (target.equals(player)) {
            ctx.messages().send(player, "seating.ride-self");
            return Cmds.OK;
        }
        RideRules.Verdict verdict = ride.mount(player, target);
        ride.report(player, verdict, target.getName());
        return Cmds.OK;
    }

    private int rideStop(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        if (player.getVehicle() == null) {
            ctx.messages().send(player, "seating.ride-not-riding");
            return Cmds.OK;
        }
        dismount(player);
        return Cmds.OK;
    }

    private int rideStopOther(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.argPlayer(c, "target");
        CommandSender sender = c.getSource().getSender();
        if (target.getVehicle() == null) {
            ctx.messages().send(sender, "seating.ride-not-riding-other", Msg.unparsed("player", target.getName()));
            return Cmds.OK;
        }
        onPlayer(target, sender, () -> dismount(target));
        if (!target.equals(sender)) {
            ctx.messages().send(sender, "seating.ride-stopped-other", Msg.unparsed("player", target.getName()));
        }
        return Cmds.OK;
    }

    private int launch(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        if (player.getVehicle() == null) {
            ctx.messages().send(player, "seating.ride-not-riding");
            return Cmds.OK;
        }
        double power = Cmds.hasArgument(c, "power") ? DoubleArgumentType.getDouble(c, "power") : 1.0;
        ride.launch(player, power);
        if (!config.get().ride.launchFallDamage) {
            player.setFallDistance(0);
        }
        ctx.messages().send(player, "seating.ride-launched", Msg.number("power", RideRules.launchPower(power)));
        return Cmds.OK;
    }

    private void dismount(Player player) {
        ride.dismount(player);
        ctx.messages().send(player, "seating.ride-stopped");
    }

    private boolean configRideEnabled() {
        return config.get().ride.enabled;
    }

    // ---- /seating ----

    private int help(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "seating.help");
        return Cmds.OK;
    }

    private int sweep(CommandContext<CommandSourceStack> c) {
        int removed = seats.sweepTracked();
        CommandSender sender = c.getSource().getSender();
        if (removed == 0) {
            ctx.messages().send(sender, "seating.sweep-none");
        } else {
            ctx.messages().send(sender, "seating.sweep-result", Msg.number("count", removed));
        }
        return Cmds.OK;
    }

    /** Runs an action on the target's own region thread and reports failures instead of hiding them. */
    private void onPlayer(Player target, CommandSender sender, Runnable action) {
        ctx.scheduler().supplyOnEntity(target, () -> {
            action.run();
            return null;
        }).exceptionally(error -> {
            if (!(error instanceof Scheduler.EntityRetiredException)) {
                ctx.logger().error("Seating command for {} failed", target.getUniqueId(), error);
                report(sender, "core.error");
            }
            return null;
        });
    }

    private void report(CommandSender recipient, String key) {
        if (recipient instanceof Player player && !Bukkit.isOwnedByCurrentRegion(player)) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key), null);
        } else {
            ctx.messages().send(recipient, key);
        }
    }
}
