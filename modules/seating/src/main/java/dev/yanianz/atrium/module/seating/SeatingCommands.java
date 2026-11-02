package dev.yanianz.atrium.module.seating;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /sit, /lay, /crawl and the small /seating admin command. */
final class SeatingCommands {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final SeatManager seats;
    private final CrawlManager crawl;

    SeatingCommands(ModuleContext ctx, SeatManager seats, CrawlManager crawl) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.seats = seats;
        this.crawl = crawl;
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
        ctx.command(CommandSpec.of(Cmds.literal("seating", SeatingModule.PERM_ADMIN)
                .executes(this::help)
                .then(Commands.literal("help").executes(this::help))
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
