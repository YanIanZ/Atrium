package dev.yanianz.atrium.module.teleport;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.homes.HomeService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** /spawn, /setspawn, first-join spawn and the respawn order. */
final class SpawnCommands implements Listener {
    static final String PERM_SPAWN = "atrium.spawn";
    static final String PERM_SPAWN_OTHERS = "atrium.spawn.others";
    static final String PERM_SETSPAWN = "atrium.setspawn";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final Messages messages;
    private final TeleportServiceImpl service;
    private final ConfigHandle<TeleportConfig> config;
    /**
     * Homes loaded when their owner died (the respawn event itself cannot wait for storage). A home is only
     * remembered when the configured order can actually use it.
     */
    private final Map<UUID, StoredLocation> homeAtDeath = new ConcurrentHashMap<>();

    SpawnCommands(ModuleContext ctx, TeleportServiceImpl service, ConfigHandle<TeleportConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.service = service;
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("spawn", PERM_SPAWN)
                .executes(this::spawn)
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::spawn))
                .build(), "Teleport to the spawn"));
        ctx.command(CommandSpec.of(Cmds.literal("setspawn", PERM_SETSPAWN)
                .executes(c -> setSpawn(c, Kind.WORLD))
                .then(Cmds.literal("world").executes(c -> setSpawn(c, Kind.WORLD)))
                .then(Cmds.literal("default").executes(c -> setSpawn(c, Kind.DEFAULT)))
                .then(Cmds.literal("firstjoin").executes(c -> setSpawn(c, Kind.FIRST_JOIN)))
                .build(), "Set the spawn of this world, the default spawn or the first-join spawn"));
    }

    private enum Kind { WORLD, DEFAULT, FIRST_JOIN }

    private int spawn(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", PERM_SPAWN_OTHERS);
        CommandSender sender = c.getSource().getSender();
        Optional<Location> spawn = service.spawnLocation(target.getWorld());
        if (spawn.isEmpty()) {
            throw cmds.fail(c.getSource(), "teleport.spawn-none");
        }
        boolean self = target.equals(sender);
        TeleportOptions options = self ? TeleportOptions.DEFAULT : TeleportOptions.IMMEDIATE;
        service.teleport(target, spawn.get(), TeleportCause.SPAWN, options).thenAccept(result -> {
            if (result != TeleportResult.SUCCESS) {
                return;
            }
            messages.send(target, "teleport.spawn");
            if (!self) {
                messages.send(sender, "teleport.spawn-other", Msg.unparsed("player", target.getName()));
            }
        });
        return Cmds.OK;
    }

    private int setSpawn(CommandContext<CommandSourceStack> c, Kind kind) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Location here = self.getLocation();
        World world = here.getWorld();
        StoredLocation stored = StoredLocation.of(here);
        String id = switch (kind) {
            case WORLD -> config.get().spawn.perWorld ? TeleportStore.worldSpawnId(world.getName()) : TeleportStore.DEFAULT_SPAWN;
            case DEFAULT -> TeleportStore.DEFAULT_SPAWN;
            case FIRST_JOIN -> TeleportStore.FIRST_JOIN_SPAWN;
        };
        service.setSpawn(id, stored).whenComplete((v, error) -> {
            if (error != null) {
                ctx.logger().error("Saving spawn {} failed", id, error);
                messages.send(self, "teleport.save-failed");
                return;
            }
            String key = switch (kind) {
                case WORLD -> id.equals(TeleportStore.DEFAULT_SPAWN) ? "teleport.setspawn-default" : "teleport.setspawn-world";
                case DEFAULT -> "teleport.setspawn-default";
                case FIRST_JOIN -> "teleport.setspawn-first-join";
            };
            messages.send(self, key, Msg.unparsed("world", world.getName()), Msg.location("at", here));
        });
        if (kind == Kind.WORLD && config.get().spawn.syncWorldSpawn) {
            ctx.scheduler().region(here, () -> {
                try {
                    world.setSpawnLocation(here);
                } catch (RuntimeException e) {
                    ctx.logger().warn("Could not move the vanilla spawn point of {}", world.getName(), e);
                }
            });
        }
        return Cmds.OK;
    }

    /** New players start at the first-join spawn (Paper fires this before the player is in a world). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        if (!event.isNewPlayer() || !config.get().spawn.teleportOnFirstJoin) {
            return;
        }
        service.firstJoinSpawn().ifPresent(event::setSpawnLocation);
    }

    /**
     * Loads the home of a dying player ahead of the respawn, because {@link PlayerRespawnEvent} has to answer
     * immediately and cannot wait for storage.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        UUID uuid = player.getUniqueId();
        homeAtDeath.remove(uuid);
        TeleportConfig.Spawn cfg = config.get().spawn;
        if (!cfg.respawnAtSpawn) {
            return;
        }
        List<String> order = RespawnPriority.orderFor(cfg.respawnOrder, player.getWorld().getName());
        if (!order.contains(RespawnPriority.HOME)) {
            return;
        }
        Optional<HomeService> homes = ctx.services().get(HomeService.class);
        if (homes.isEmpty()) {
            return;
        }
        homes.get().homes(uuid).whenComplete((list, error) -> {
            if (error != null) {
                ctx.logger().warn("Reading the homes of {} for the respawn order failed", player.getName(), error);
                return;
            }
            list.stream()
                    .filter(home -> home.location().toLocation().isPresent())
                    .sorted((a, b) -> Boolean.compare(!a.name().equalsIgnoreCase("home"), !b.name().equalsIgnoreCase("home")))
                    .findFirst()
                    .ifPresent(home -> homeAtDeath.put(uuid, home.location()));
        });
    }

    /** Chooses the respawn point with the configured per-world order; falls back to the vanilla choice. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        TeleportConfig.Spawn cfg = config.get().spawn;
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        StoredLocation storedHome = homeAtDeath.remove(uuid);
        if (!cfg.respawnAtSpawn) {
            return;
        }
        World world = event.getRespawnLocation().getWorld();
        Map<String, Location> available = new LinkedHashMap<>();
        if (event.isAnchorSpawn()) {
            available.put(RespawnPriority.ANCHOR, event.getRespawnLocation());
        }
        if (event.isBedSpawn()) {
            available.put(RespawnPriority.BED, event.getRespawnLocation());
        }
        service.storedSpawn(world).flatMap(StoredLocation::toLocation)
                .ifPresent(location -> available.put(RespawnPriority.SPAWN, location));
        if (storedHome != null) {
            storedHome.toLocation().ifPresent(location -> available.put(RespawnPriority.HOME, location));
        }
        List<String> order = RespawnPriority.orderFor(cfg.respawnOrder, world.getName());
        Optional<WarpService> warps = ctx.services().get(WarpService.class);
        if (warps.isPresent()) {
            for (String token : order) {
                RespawnPriority.warpName(token)
                        .flatMap(warps.get()::location)
                        .flatMap(StoredLocation::toLocation)
                        .ifPresent(location -> available.putIfAbsent(token, location));
            }
        }
        available.putIfAbsent(RespawnPriority.WORLD_SPAWN, world.getSpawnLocation());
        RespawnPriority.choose(order, available::containsKey)
                .map(available::get)
                .ifPresent(event::setRespawnLocation);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        homeAtDeath.remove(event.getPlayer().getUniqueId());
    }
}
