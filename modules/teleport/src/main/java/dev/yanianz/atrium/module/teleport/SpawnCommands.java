package dev.yanianz.atrium.module.teleport;

import com.destroystokyo.paper.event.player.PlayerPostRespawnEvent;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.homes.HomeService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.StoredTeleportService.StoredTeleportResult;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.api.world.WorldProviderRegistry;
import dev.yanianz.atrium.api.world.WorldResolver;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
import org.jspecify.annotations.Nullable;

/** /spawn, /spawn info, /setspawn, /delspawn, first-join spawn and the respawn order. */
final class SpawnCommands implements Listener {
    static final String PERM_SPAWN = "atrium.spawn";
    static final String PERM_SPAWN_OTHERS = "atrium.spawn.others";
    static final String PERM_SPAWN_INFO = "atrium.spawn.info";
    static final String PERM_SETSPAWN = "atrium.setspawn";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final Messages messages;
    private final TeleportServiceImpl service;
    private final StoredTeleports stored;
    private final ConfigHandle<TeleportConfig> config;
    /**
     * Homes loaded when their owner died (the respawn event itself cannot wait for storage). A home is only
     * remembered when the configured order can actually use it.
     */
    private final Map<UUID, StoredLocation> homeAtDeath = new ConcurrentHashMap<>();
    /** Respawn spawns whose world was not loaded at the respawn: the player is moved there right after it. */
    private final Map<UUID, StoredLocation> spawnAfterRespawn = new ConcurrentHashMap<>();

    SpawnCommands(ModuleContext ctx, TeleportServiceImpl service, StoredTeleports stored,
            ConfigHandle<TeleportConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.service = service;
        this.stored = stored;
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("spawn", PERM_SPAWN)
                .executes(this::spawn)
                .then(Cmds.literal("info", PERM_SPAWN_INFO)
                        .executes(c -> info(c, false))
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(c -> info(c, true))))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::spawn))
                .build(), "Teleport to the spawn (/spawn info shows which spawn is used and why)"));
        // Bare /setspawn sets the main spawn, as EssentialsSpawn's /setspawn did; a per-world spawn is explicit.
        ctx.command(CommandSpec.of(Cmds.literal("setspawn", PERM_SETSPAWN)
                .executes(c -> setSpawn(c, Kind.DEFAULT))
                .then(Cmds.literal("world").executes(c -> setSpawn(c, Kind.WORLD)))
                .then(Cmds.literal("default").executes(c -> setSpawn(c, Kind.DEFAULT)))
                .then(Cmds.literal("firstjoin").executes(c -> setSpawn(c, Kind.FIRST_JOIN)))
                .then(Cmds.literal("group")
                        .then(Commands.argument("group", StringArgumentType.word()).executes(c -> setSpawn(c, Kind.GROUP))))
                .build(), "Set the global main spawn (or: firstjoin; world and group <name> when enabled)"));
        ctx.command(CommandSpec.of(Cmds.literal("delspawn", PERM_SETSPAWN)
                .then(Cmds.literal("world")
                        .executes(c -> delSpawn(c, Kind.WORLD, null))
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests((c, b) -> suggestStored(b, TeleportStore.WORLD_PREFIX))
                                .executes(c -> delSpawn(c, Kind.WORLD, StringArgumentType.getString(c, "world")))))
                .then(Cmds.literal("group")
                        .then(Commands.argument("group", StringArgumentType.word())
                                .suggests((c, b) -> suggestStored(b, TeleportStore.GROUP_PREFIX))
                                .executes(c -> delSpawn(c, Kind.GROUP, StringArgumentType.getString(c, "group")))))
                .then(Cmds.literal("default").executes(c -> delSpawn(c, Kind.DEFAULT, null)))
                .then(Cmds.literal("firstjoin").executes(c -> delSpawn(c, Kind.FIRST_JOIN, null)))
                .build(), "Remove a per-world, group, default or first-join spawn"));
    }

    private enum Kind { WORLD, DEFAULT, FIRST_JOIN, GROUP }

    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestStored(
            com.mojang.brigadier.suggestion.SuggestionsBuilder builder, String prefix) {
        String typed = builder.getRemainingLowerCase();
        SpawnResolver.withPrefix(service.spawnsSnapshot(), prefix).keySet().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }

    // ---- /spawn ----

    private int spawn(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player target = cmds.selfOrOther(c, "target", PERM_SPAWN_OTHERS);
        CommandSender sender = c.getSource().getSender();
        boolean self = target.equals(sender);
        TeleportOptions options = self ? TeleportOptions.DEFAULT : TeleportOptions.IMMEDIATE;
        SpawnResolver.Choice choice = service.spawnChoice(target, target.getWorld());
        CompletableFuture<TeleportResult> moved;
        if (choice.stored()) {
            // The world may be an AWF/Multiverse/slime world that is not loaded right now: StoredTeleports loads it
            // on demand and tells the player when that is not possible, instead of silently using another spawn.
            moved = stored.teleport(target, choice.location(), TeleportCause.SPAWN, options)
                    .thenApply(StoredTeleportResult::result);
        } else {
            Optional<Location> vanilla = TeleportServiceImpl.vanillaSpawn(choice.key() == null ? null : target.getWorld());
            if (vanilla.isEmpty()) {
                throw cmds.fail(c.getSource(), "teleport.spawn-none");
            }
            moved = service.teleport(target, vanilla.get(), TeleportCause.SPAWN, options);
        }
        moved.whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Teleporting {} to the {} failed", target.getName(), choice.label(), error);
                return;
            }
            if (result == TeleportResult.SUCCESS) {
                messages.send(target, "teleport.spawn");
                if (!self) {
                    messages.send(sender, "teleport.spawn-other", Msg.unparsed("player", target.getName()));
                }
            } else if (!self) {
                messages.send(sender, "teleport.spawn-other-failed", Msg.unparsed("player", target.getName()),
                        Msg.unparsed("spawn", choice.label()), Msg.unparsed("result", result.name().toLowerCase(Locale.ROOT)));
            }
        });
        return Cmds.OK;
    }

    // ---- /spawn info ----

    private int info(CommandContext<CommandSourceStack> c, boolean withPlayer) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player player = withPlayer ? cmds.argPlayer(c, "player") : sender instanceof Player self ? self : null;
        TeleportConfig.Spawn cfg = config.get().spawn;
        Map<String, StoredLocation> spawns = service.spawnsSnapshot();
        messages.send(sender, "teleport.spawn-info-header");
        StoredLocation main = spawns.get(TeleportStore.DEFAULT_SPAWN);
        messages.send(sender, "teleport.spawn-info-main", value(sender, main));
        StoredLocation first = spawns.get(TeleportStore.FIRST_JOIN_SPAWN);
        messages.send(sender, "teleport.spawn-info-first-join", value(sender, first),
                Msg.unparsed("state", cfg.teleportOnFirstJoin ? "on" : "off"),
                Msg.unparsed("uses", SpawnResolver.forFirstJoin(spawns).label()));
        Map<String, StoredLocation> worlds = SpawnResolver.withPrefix(spawns, TeleportStore.WORLD_PREFIX);
        messages.send(sender, cfg.perWorldSpawns ? "teleport.spawn-info-per-world" : "teleport.spawn-info-per-world-off",
                Msg.number("count", worlds.size()));
        worlds.forEach((world, location) -> messages.send(sender, "teleport.spawn-info-world-entry",
                Msg.unparsed("world", world), value(sender, location)));
        Map<String, StoredLocation> groups = SpawnResolver.withPrefix(spawns, TeleportStore.GROUP_PREFIX);
        messages.send(sender, cfg.groupSpawns ? "teleport.spawn-info-groups" : "teleport.spawn-info-groups-off",
                Msg.number("count", groups.size()));
        for (String group : SpawnResolver.groupOrder(groups.keySet(), cfg.groupOrder)) {
            messages.send(sender, "teleport.spawn-info-group-entry", Msg.unparsed("group", group),
                    Msg.unparsed("permission", SpawnResolver.groupPermission(group)), value(sender, groups.get(group)));
        }
        if (main != null && cfg.perWorldSpawns && worlds.containsKey(main.world()) && !worlds.get(main.world()).equals(main)) {
            messages.send(sender, "teleport.spawn-info-shadow", Msg.unparsed("world", main.world()));
        }
        List<String> order;
        if (player != null) {
            World world = player.getWorld();
            SpawnResolver.Choice choice = service.spawnChoice(player, world);
            messages.send(sender, "teleport.spawn-info-resolved", Msg.unparsed("player", player.getName()),
                    Msg.unparsed("world", world.getName()), Msg.unparsed("spawn", choice.label()),
                    value(sender, choice.location()));
            order = RespawnPriority.orderFor(cfg.respawnOrder, world.getName());
            messages.send(sender, "teleport.spawn-info-respawn", Msg.unparsed("world", world.getName()),
                    Msg.unparsed("order", String.join(", ", order)), Msg.unparsed("state", cfg.respawnAtSpawn ? "on" : "off"));
        } else {
            for (World world : Bukkit.getWorlds()) {
                SpawnResolver.Choice choice = service.spawnChoice(null, world);
                messages.send(sender, "teleport.spawn-info-resolved-world", Msg.unparsed("world", world.getName()),
                        Msg.unparsed("spawn", choice.label()), value(sender, choice.location()));
            }
            order = RespawnPriority.orderFor(cfg.respawnOrder, "*");
            messages.send(sender, "teleport.spawn-info-respawn", Msg.unparsed("world", "*"),
                    Msg.unparsed("order", String.join(", ", order)), Msg.unparsed("state", cfg.respawnAtSpawn ? "on" : "off"));
        }
        if (cfg.respawnAtSpawn && !order.isEmpty() && !order.getFirst().equals(RespawnPriority.SPAWN)) {
            messages.send(sender, "teleport.spawn-info-respawn-note", Msg.unparsed("first", order.getFirst()));
        }
        return Cmds.OK;
    }

    /** {@code <value>}: a location with the state of its world, or "not set". */
    private net.kyori.adventure.text.minimessage.tag.resolver.TagResolver value(CommandSender sender,
            @Nullable StoredLocation location) {
        Component text;
        if (location == null) {
            text = messages.render(sender, "teleport.spawn-info-unset");
        } else {
            String where = location.world() + " " + location.blockX() + " " + location.blockY() + " " + location.blockZ();
            text = messages.render(sender, worldStateKey(location.world()), Msg.unparsed("where", where),
                    Msg.unparsed("provider", providerOf(location.world())));
        }
        return Msg.component("value", text);
    }

    private String worldStateKey(String world) {
        if (Bukkit.getWorld(world) != null) {
            return "teleport.spawn-info-loaded";
        }
        return providerOf(world).isEmpty() ? "teleport.spawn-info-missing" : "teleport.spawn-info-on-demand";
    }

    private String providerOf(String world) {
        return ctx.services().get(WorldProviderRegistry.class)
                .flatMap(registry -> {
                    try {
                        return registry.find(world);
                    } catch (RuntimeException e) {
                        ctx.logger().debug("World provider lookup of {} failed", world, e);
                        return Optional.empty();
                    }
                })
                .map(match -> match.provider().displayName())
                .orElse("");
    }

    // ---- /setspawn, /delspawn ----

    private int setSpawn(CommandContext<CommandSourceStack> c, Kind kind) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        TeleportConfig.Spawn cfg = config.get().spawn;
        if (kind == Kind.WORLD && !cfg.perWorldSpawns) {
            // One global spawn unless the admin opted in: never create a per-world spawn that /spawn would ignore.
            throw cmds.fail(c.getSource(), "teleport.setspawn-world-off");
        }
        if (kind == Kind.GROUP && !cfg.groupSpawns) {
            throw cmds.fail(c.getSource(), "teleport.setspawn-group-off");
        }
        Location here = self.getLocation();
        World world = here.getWorld();
        StoredLocation location = StoredLocation.of(here);
        String group = kind == Kind.GROUP ? StringArgumentType.getString(c, "group").toLowerCase(Locale.ROOT) : null;
        String id = switch (kind) {
            case WORLD -> TeleportStore.worldSpawnId(world.getName());
            case DEFAULT -> TeleportStore.DEFAULT_SPAWN;
            case FIRST_JOIN -> TeleportStore.FIRST_JOIN_SPAWN;
            case GROUP -> TeleportStore.groupSpawnId(group);
        };
        service.setSpawn(id, location).whenComplete((v, error) -> {
            if (error != null) {
                ctx.logger().error("Saving spawn {} failed", id, error);
                messages.send(self, "teleport.save-failed");
                return;
            }
            String key = switch (kind) {
                case WORLD -> "teleport.setspawn-world";
                case DEFAULT -> "teleport.setspawn-default";
                case FIRST_JOIN -> "teleport.setspawn-first-join";
                case GROUP -> "teleport.setspawn-group";
            };
            messages.send(self, key, Msg.unparsed("world", world.getName()), Msg.location("at", here),
                    Msg.unparsed("group", group == null ? "" : group),
                    Msg.unparsed("permission", group == null ? "" : SpawnResolver.groupPermission(group)));
        });
        if (kind == Kind.WORLD && cfg.syncWorldSpawn) {
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

    private int delSpawn(CommandContext<CommandSourceStack> c, Kind kind, @Nullable String name) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String target = name;
        if (kind == Kind.WORLD && target == null) {
            target = cmds.player(c.getSource()).getWorld().getName();
        }
        String id = switch (kind) {
            case WORLD -> TeleportStore.worldSpawnId(target);
            case GROUP -> TeleportStore.groupSpawnId(target);
            case DEFAULT -> TeleportStore.DEFAULT_SPAWN;
            case FIRST_JOIN -> TeleportStore.FIRST_JOIN_SPAWN;
        };
        String label = switch (kind) {
            case WORLD -> "per-world spawn of '" + target + "'";
            case GROUP -> "group spawn '" + target.toLowerCase(Locale.ROOT) + "'";
            case DEFAULT -> "main spawn";
            case FIRST_JOIN -> "first-join spawn";
        };
        service.deleteSpawn(id).whenComplete((deleted, error) -> {
            if (error != null) {
                ctx.logger().error("Deleting spawn {} failed", id, error);
                messages.send(sender, "teleport.save-failed");
                return;
            }
            messages.send(sender, deleted ? "teleport.delspawn" : "teleport.delspawn-none", Msg.unparsed("spawn", label));
        });
        return Cmds.OK;
    }

    // ---- first join ----

    /**
     * New players start at the first-join spawn, else the main spawn (Paper fires this before the player is in a
     * world, off the tick threads, so an AWF/Multiverse/slime world can be loaded here with a bounded wait).
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        TeleportConfig.Spawn cfg = config.get().spawn;
        if (!event.isNewPlayer() || !cfg.teleportOnFirstJoin) {
            return;
        }
        StoredLocation target = service.firstJoinStored().orElse(null);
        if (target == null) {
            return;
        }
        Optional<Location> loaded = target.toLocation();
        if (loaded.isPresent()) {
            event.setSpawnLocation(loaded.get());
            return;
        }
        if (cfg.firstJoinWorldLoadSeconds <= 0 || !event.isAsynchronous()) {
            ctx.logger().warn("First-join spawn world '{}' is not loaded; the new player starts at the server's spawn",
                    target.world());
            return;
        }
        WorldResolver resolver = ctx.services().get(WorldResolver.class).orElseGet(WorldResolver::bukkit);
        try {
            WorldResolver.Resolution resolution = resolver.lookup(target.world())
                    .get(cfg.firstJoinWorldLoadSeconds, TimeUnit.SECONDS);
            if (resolution.available()) {
                event.setSpawnLocation(new Location(resolution.world(), target.x(), target.y(), target.z(),
                        target.yaw(), target.pitch()));
            } else {
                ctx.logger().warn("First-join spawn world '{}' is not available ({} {}); the new player starts at the "
                        + "server's spawn", target.world(), resolution.status(), resolution.detail());
            }
        } catch (TimeoutException e) {
            ctx.logger().warn("First-join spawn world '{}' did not load within {} s; the new player starts at the "
                    + "server's spawn", target.world(), cfg.firstJoinWorldLoadSeconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            ctx.logger().warn("Loading the first-join spawn world '{}' failed", target.world(), e);
        }
    }

    // ---- respawn ----

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
        spawnAfterRespawn.remove(uuid);
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
        StoredLocation spawn = service.storedSpawn(player, world).orElse(null);
        // A spawn in a world that is not loaded cannot be used by the event itself: the player respawns at the
        // next point of the order and is moved to the spawn (loading its world) right after the respawn.
        boolean spawnPending = false;
        if (spawn != null) {
            Optional<Location> loaded = spawn.toLocation();
            if (loaded.isPresent()) {
                available.put(RespawnPriority.SPAWN, loaded.get());
            } else {
                spawnPending = true;
            }
        }
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
        boolean pending = spawnPending;
        Optional<String> chosen = RespawnPriority.choose(order,
                token -> available.containsKey(token) || (pending && token.equals(RespawnPriority.SPAWN)));
        if (chosen.isPresent() && pending && chosen.get().equals(RespawnPriority.SPAWN)) {
            spawnAfterRespawn.put(uuid, spawn);
            List<String> rest = new ArrayList<>(order);
            rest.remove(RespawnPriority.SPAWN);
            chosen = RespawnPriority.choose(rest, available::containsKey);
        }
        chosen.map(available::get).ifPresent(event::setRespawnLocation);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPostRespawn(PlayerPostRespawnEvent event) {
        Player player = event.getPlayer();
        StoredLocation spawn = spawnAfterRespawn.remove(player.getUniqueId());
        if (spawn == null) {
            return;
        }
        stored.teleport(player, spawn, TeleportCause.SPAWN, TeleportOptions.IMMEDIATE).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Moving {} to the spawn in {} after the respawn failed", player.getName(), spawn.world(), error);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        homeAtDeath.remove(event.getPlayer().getUniqueId());
        spawnAfterRespawn.remove(event.getPlayer().getUniqueId());
    }
}
