package dev.yanianz.atrium.module.teleport;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /jump, /down, /tppos, /tpo, /tpallworld and /tpbypass. */
final class NavigationCommands {
    static final String PERM_JUMP = "atrium.jump";
    static final String PERM_DOWN = "atrium.down";
    static final String PERM_TPPOS = "atrium.tppos";
    static final String PERM_TPO = "atrium.tpo";
    static final String PERM_TPALLWORLD = "atrium.tpallworld";
    /** Numeric: atrium.jump.distance.<n> raises the reach of /jump. */
    static final String PERM_JUMP_DISTANCE = "atrium.jump.distance";
    /** Hard safety cap for the /jump ray trace, whatever a permission says. */
    private static final int MAX_JUMP_REACH = 256;

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final Messages messages;
    private final TeleportServiceImpl service;
    private final ConfigHandle<TeleportConfig> config;

    NavigationCommands(ModuleContext ctx, TeleportServiceImpl service, ConfigHandle<TeleportConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.service = service;
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("jump", PERM_JUMP)
                .executes(this::jump)
                .build(), "Leap to the block you are looking at"));
        ctx.command(CommandSpec.of(Cmds.literal("down", PERM_DOWN)
                .executes(this::down)
                .build(), "Teleport to the lowest safe spot below you"));
        ctx.command(CommandSpec.of(Cmds.literal("tppos", PERM_TPPOS)
                .executes(c -> usage(c, "teleport.usage-tppos"))
                .then(Commands.argument("position", new PositionArgument()).executes(c -> position(c, false))
                        .then(Commands.argument("world", ArgumentTypes.world()).executes(c -> position(c, false))))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("position", new PositionArgument()).executes(c -> position(c, false))
                                .then(Commands.argument("world", ArgumentTypes.world()).executes(c -> position(c, false)))))
                .build(), "Teleport to coordinates: /tppos [player] <x> <y> <z> [world]"));
        ctx.command(CommandSpec.of(Cmds.literal("tpo", PERM_TPO)
                .executes(c -> usage(c, "teleport.usage-tpo"))
                .then(Commands.argument("position", new PositionArgument()).executes(c -> position(c, true))
                        .then(Commands.argument("world", ArgumentTypes.world()).executes(c -> position(c, true))))
                .then(Commands.argument("target", ArgumentTypes.player())
                        .then(Commands.argument("destination", ArgumentTypes.player()).executes(this::tpoToPlayer))
                        .then(Commands.argument("position", new PositionArgument()).executes(c -> position(c, true))
                                .then(Commands.argument("world", ArgumentTypes.world()).executes(c -> position(c, true)))))
                .build(), "Force a teleport, skipping the safety check: /tpo [player] <target|coords>"));
        ctx.command(CommandSpec.of(Cmds.literal("tpallworld", PERM_TPALLWORLD)
                .executes(c -> usage(c, "teleport.usage-tpallworld"))
                .then(Commands.argument("world", ArgumentTypes.world()).executes(this::tpAllWorld))
                .build(), "Send every player of your world to a spawn in another world"));
        ctx.command(CommandSpec.of(Cmds.literal("tpbypass", TeleportServiceImpl.PERM_TPBYPASS)
                .executes(this::tpBypass)
                .build(), "Force the destination of your last unsafe teleport"));
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        messages.send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    // ---- /jump ----

    private int jump(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        int configured = Math.max(1, config.get().jump.maxDistance);
        int reach = Math.max(1, Math.min(MAX_JUMP_REACH, Permissions.limit(self, PERM_JUMP_DISTANCE, configured)));
        Block target = self.getTargetBlockExact(reach, FluidCollisionMode.NEVER);
        if (target == null || target.isEmpty() || target.isLiquid()) {
            throw cmds.fail(c.getSource(), "teleport.jump-none");
        }
        Location at = self.getLocation();
        // Stand on top of the aimed block; the search is short upwards so a wall does not throw the player far.
        Location wanted = new Location(target.getWorld(), target.getX() + 0.5, target.getY() + 1, target.getZ() + 0.5,
                at.getYaw(), at.getPitch());
        if (!target.getWorld().getWorldBorder().isInside(wanted)) {
            throw cmds.fail(c.getSource(), "teleport.position-out-of-range");
        }
        TeleportConfig.SafeLanding window = window(Math.min(8, reach), 1);
        service.landing().find(wanted, window).whenComplete((spot, error) -> {
            if (error != null) {
                ctx.logger().error("/jump search failed for {}", self.getName(), error);
                messages.send(self, "core.error");
                return;
            }
            if (spot.isEmpty()) {
                messages.send(self, "teleport.jump-none");
                return;
            }
            service.teleport(self, spot.get(), TeleportCause.COMMAND, TeleportOptions.DEFAULT.withSafety(TeleportOptions.Safety.SKIP))
                    .thenAccept(result -> {
                        if (result == TeleportResult.SUCCESS) {
                            messages.send(self, "teleport.jump");
                        }
                    });
        });
        return Cmds.OK;
    }

    // ---- /down ----

    private int down(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Location at = self.getLocation();
        int fromY = at.getBlockY();
        service.landing().down(at, config.get().safeLanding).whenComplete((spot, error) -> {
            if (error != null) {
                ctx.logger().error("/down search failed for {} at {}", self.getName(), fromY, error);
                messages.send(self, "core.error");
                return;
            }
            if (spot.isEmpty() || spot.get().getBlockY() >= fromY) {
                messages.send(self, "teleport.down-none");
                return;
            }
            if (!spot.get().getWorld().getWorldBorder().isInside(spot.get())) {
                messages.send(self, "teleport.position-out-of-range");
                return;
            }
            service.teleport(self, spot.get(), TeleportCause.COMMAND,
                            TeleportOptions.DEFAULT.withSafety(TeleportOptions.Safety.SKIP))
                    .thenAccept(result -> {
                        if (result == TeleportResult.SUCCESS) {
                            messages.send(self, "teleport.down");
                        }
                    });
        });
        return Cmds.OK;
    }

    // ---- /tppos and /tpo ----

    /** /tppos and /tpo: to typed coordinates, the sender or a named player. */
    private int position(CommandContext<CommandSourceStack> c, boolean force) throws CommandSyntaxException {
        Player who = Cmds.hasArgument(c, "target") ? cmds.argPlayer(c, "target") : cmds.player(c.getSource());
        requireOthers(c, who);
        Position position = c.getArgument("position", Position.class);
        World world = Cmds.hasArgument(c, "world") ? c.getArgument("world", World.class) : null;
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().supplyOnEntity(who, who::getLocation).thenCompose(base -> {
            Location target = position.resolve(base, world);
            if (!RelativeCoordinate.withinHorizontalLimit(target.getX())
                    || !RelativeCoordinate.withinHorizontalLimit(target.getZ())) {
                messages.send(sender, "teleport.position-out-of-range");
                return CompletableFuture.completedFuture(TeleportResult.FAILED);
            }
            TeleportOptions options = force
                    ? TeleportOptions.IMMEDIATE.withSafety(TeleportOptions.Safety.SKIP)
                    : TeleportOptions.IMMEDIATE;
            return service.teleport(who, target, TeleportCause.COMMAND, options).thenApply(result -> {
                if (result == TeleportResult.SUCCESS) {
                    messages.send(who, "teleport.tp-to-position", Msg.location("to", target));
                    if (!sender.equals(who)) {
                        messages.send(sender, "teleport.tp-other-to-position", Msg.location("to", target),
                                Msg.unparsed("player", who.getName()));
                    }
                }
                return result;
            });
        }).whenComplete((result, error) -> {
            if (error != null) {
                messages.send(sender, "core.player-not-found");
            }
        });
        return Cmds.OK;
    }

    /** /tpo &lt;player&gt; &lt;destination&gt;: force one player onto another. */
    private int tpoToPlayer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player who = cmds.argPlayer(c, "target");
        Player destination = cmds.argPlayer(c, "destination");
        if (who.equals(destination)) {
            throw cmds.fail(c.getSource(), "teleport.cannot-self");
        }
        requireOthers(c, who);
        CommandSender sender = c.getSource().getSender();
        ctx.scheduler().supplyOnEntity(destination, destination::getLocation).thenCompose(location -> service.teleport(
                who, location, TeleportCause.COMMAND,
                TeleportOptions.IMMEDIATE.withSafety(TeleportOptions.Safety.SKIP))).whenComplete((result, error) -> {
            if (error != null || result != TeleportResult.SUCCESS) {
                return;
            }
            messages.send(who, "teleport.tp-to-player", Msg.unparsed("player", destination.getName()));
            if (!sender.equals(who)) {
                messages.send(sender, "teleport.tp-other-to-player", Msg.unparsed("player", who.getName()),
                        Msg.unparsed("target", destination.getName()));
            }
        });
        return Cmds.OK;
    }

    // ---- /tpallworld ----

    private int tpAllWorld(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        World destination = c.getArgument("world", World.class);
        // Never leave the players in another world than the one that was asked for.
        Location target = service.storedSpawn(destination).flatMap(StoredLocation::toLocation)
                .orElseGet(destination::getSpawnLocation);
        World source = self.getWorld();
        List<Player> players = List.copyOf(source.getPlayers());
        for (Player player : players) {
            service.teleport(player, target, TeleportCause.SPAWN, TeleportOptions.IMMEDIATE)
                    .thenAccept(result -> {
                        if (result == TeleportResult.SUCCESS) {
                            messages.send(player, "teleport.tpallworld-target", Msg.unparsed("world", destination.getName()));
                        }
                    });
        }
        messages.send(self, "teleport.tpallworld", Msg.number("count", players.size()),
                Msg.unparsed("world", destination.getName()));
        return Cmds.OK;
    }

    // ---- /tpbypass ----

    private int tpBypass(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Optional<Location> spot = service.takeFailedSpot(self.getUniqueId());
        if (spot.isEmpty()) {
            throw cmds.fail(c.getSource(), "teleport.tpbypass-none");
        }
        service.teleport(self, spot.get(), TeleportCause.COMMAND,
                TeleportOptions.IMMEDIATE.withSafety(TeleportOptions.Safety.SKIP)).thenAccept(result -> {
                    if (result == TeleportResult.SUCCESS) {
                        messages.send(self, "teleport.tpbypass");
                    }
                });
        return Cmds.OK;
    }

    // ---- helpers ----

    private void requireOthers(CommandContext<CommandSourceStack> c, Player who) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        if (!who.equals(sender) && !sender.hasPermission(TeleportCommands.PERM_TP_OTHERS)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others",
                    Msg.unparsed("permission", TeleportCommands.PERM_TP_OTHERS));
        }
    }

    /** A short safe-landing window used after a target block was chosen (never a big spiral). */
    private static TeleportConfig.SafeLanding window(int up, int down) {
        TeleportConfig.SafeLanding window = new TeleportConfig.SafeLanding();
        window.searchUp = Math.max(0, up);
        window.searchDown = Math.max(0, down);
        window.searchRadius = 0;
        window.allowWater = true;
        return window;
    }
}
