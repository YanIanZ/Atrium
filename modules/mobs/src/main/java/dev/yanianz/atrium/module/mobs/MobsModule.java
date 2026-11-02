package dev.yanianz.atrium.module.mobs;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.mobs.SpawnerChangeEvent;
import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.util.RayTraceResult;

/** /spawnmob, /killall, /mobinfo and /setspawner. All world access happens on the owning region thread. */
public final class MobsModule implements AtriumModule {
    private static final List<String> CATEGORIES = List.of("monsters", "animals", "mobs", "items", "xp");
    private static final List<String> FLAGS = List.of("named", "tamed");

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<MobsConfig> config;
    private ProtectionService protection;

    @Override
    public String id() {
        return "mobs";
    }

    @Override
    public String description() {
        return "Spawn, remove and inspect mobs; change spawners";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("integrations");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(MobsConfig.class, MobsConfig::new);
        this.protection = ctx.services().get(ProtectionService.class).orElse(null);
        registerPermissions();

        SuggestionProvider<CommandSourceStack> stackSuggestions = (c, builder) -> {
            String typed = builder.getRemainingLowerCase();
            int plus = typed.lastIndexOf('+');
            String prefix = plus < 0 ? "" : typed.substring(0, plus + 1);
            String last = typed.substring(plus + 1);
            CommandSender sender = c.getSource().getSender();
            for (EntityType type : EntityTypes.spawnableTypes()) {
                String name = EntityTypes.name(type);
                if (name.startsWith(last) && sender.hasPermission("atrium.spawnmob." + EntityTypes.node(type))) {
                    builder.suggest(prefix + name);
                }
            }
            return builder.buildFuture();
        };
        ctx.command(CommandSpec.of(Cmds.literal("spawnmob", "atrium.spawnmob")
                .executes(c -> {
                    ctx.messages().send(c.getSource().getSender(), "mobs.spawn-usage",
                            Msg.number("depth", config.get().maxStackDepth()), Msg.number("max", config.get().maxAmount()));
                    return Cmds.OK;
                })
                .then(Commands.argument("type", StringArgumentType.word()).suggests(stackSuggestions)
                        .executes(this::spawnmob)
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1)).executes(this::spawnmob)
                                .then(Commands.argument("player", ArgumentTypes.player()).executes(this::spawnmob))))
                .build(), "Spawn mobs at your or another player's location", "spawnentity"));

        SuggestionProvider<CommandSourceStack> killTypes = (c, builder) -> {
            String typed = builder.getRemainingLowerCase();
            CATEGORIES.stream().filter(s -> s.startsWith(typed)).forEach(builder::suggest);
            EntityTypes.registry().stream().filter(t -> t != EntityType.PLAYER).map(EntityTypes::name)
                    .filter(s -> s.startsWith(typed)).forEach(builder::suggest);
            return builder.buildFuture();
        };
        ctx.command(CommandSpec.of(Cmds.literal("killall", "atrium.killall")
                .executes(this::killall)
                .then(Commands.argument("type", StringArgumentType.word()).suggests(killTypes).executes(this::killall)
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1)).executes(this::killall)
                                .then(Commands.argument("flags", StringArgumentType.greedyString())
                                        .suggests(Cmds.suggest(() -> FLAGS)).executes(this::killall))))
                .build(), "Remove mobs around you (players, named and tamed mobs are kept)", "butcher"));

        ctx.command(CommandSpec.of(Cmds.literal("mobinfo", "atrium.mobinfo").executes(this::mobinfo).build(),
                "Show information about the entity you are looking at"));

        ctx.command(CommandSpec.of(Cmds.literal("setspawner", "atrium.setspawner")
                .executes(this::spawnerUsage)
                .then(Commands.argument("type", ArgumentTypes.resource(RegistryKey.ENTITY_TYPE)).executes(this::setspawner))
                .build(), "Change the mob type of the spawner you are looking at"));
    }

    private void registerPermissions() {
        ctx.permission("atrium.spawnmob", PermissionDefault.OP, "Use /spawnmob (each type also needs atrium.spawnmob.<type>)");
        ctx.permission("atrium.spawnmob.others", PermissionDefault.OP, "Spawn mobs at another player's location");
        ctx.permission("atrium.killall", PermissionDefault.OP, "Remove mobs in a radius");
        ctx.permission("atrium.killall.named", PermissionDefault.OP, "Let /killall remove named mobs");
        ctx.permission("atrium.killall.tamed", PermissionDefault.OP, "Let /killall remove tamed mobs");
        ctx.permission("atrium.mobinfo", PermissionDefault.TRUE, "Inspect the entity you are looking at");
        ctx.permission("atrium.setspawner", PermissionDefault.OP, "Use /setspawner (each type also needs atrium.setspawner.<type>)");
        Map<String, Boolean> spawnChildren = new HashMap<>();
        Map<String, Boolean> spawnerChildren = new HashMap<>();
        for (EntityType type : EntityTypes.spawnableTypes()) {
            String node = EntityTypes.node(type);
            ctx.permission("atrium.spawnmob." + node, PermissionDefault.OP, "Spawn " + EntityTypes.name(type));
            spawnChildren.put("atrium.spawnmob." + node, true);
            if (EntityTypes.spawnerType(type)) {
                ctx.permission("atrium.setspawner." + node, PermissionDefault.OP, "Set spawners to " + EntityTypes.name(type));
                spawnerChildren.put("atrium.setspawner." + node, true);
            }
        }
        ctx.permissions().register("atrium.spawnmob.*", PermissionDefault.OP, "Spawn every entity type", spawnChildren);
        ctx.permissions().register("atrium.setspawner.*", PermissionDefault.OP, "Set spawners to every type", spawnerChildren);
    }

    // ---- /spawnmob ------------------------------------------------------------------------------

    private record SpawnResult(int spawned, SpawnPlan.Plan plan, boolean failed, boolean protectedDenied) {}

    private int spawnmob(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        MobsConfig cfg = config.get();
        CommandSender sender = c.getSource().getSender();
        String spec = StringArgumentType.getString(c, "type");
        List<String> tokens;
        try {
            tokens = SpawnPlan.tokens(spec);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "mobs.unknown-type", Msg.unparsed("type", spec));
        }
        if (tokens.size() > cfg.maxStackDepth()) {
            throw cmds.fail(c.getSource(), "mobs.stack-too-deep", Msg.number("max", cfg.maxStackDepth()));
        }
        SpawnPlan.Resolved<EntityType> resolved = SpawnPlan.resolve(tokens,
                t -> EntityTypes.parse(t).filter(EntityTypes::spawnable));
        if (!resolved.ok()) {
            throw cmds.fail(c.getSource(), "mobs.unknown-type", Msg.unparsed("type", resolved.unknown()));
        }
        for (EntityType type : new LinkedHashSet<>(resolved.values())) {
            String node = "atrium.spawnmob." + EntityTypes.node(type);
            if (!sender.hasPermission(node)) {
                throw cmds.fail(c.getSource(), "mobs.no-permission-type", Msg.unparsed("type", EntityTypes.name(type)),
                        Msg.unparsed("permission", node));
            }
        }
        int amount = Cmds.hasArgument(c, "amount") ? IntegerArgumentType.getInteger(c, "amount") : 1;
        if (amount > cfg.maxAmount()) {
            throw cmds.fail(c.getSource(), "mobs.amount-too-high", Msg.number("max", cfg.maxAmount()));
        }
        Player target = cmds.selfOrOther(c, "player", "atrium.spawnmob.others");
        List<EntityType> stack = resolved.values();
        int maxTotal = cfg.maxEntitiesPerCommand();
        int chunkCap = cfg.maxEntitiesPerChunk();
        Player actor = sender instanceof Player player ? player : null;
        ctx.scheduler().supplyOnEntity(target, target::getLocation)
                .thenCompose(location -> ctx.scheduler().supplyAt(location,
                        () -> spawnAt(location, stack, amount, maxTotal, chunkCap, actor)))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        ctx.logger().warn("/spawnmob for {} at {} failed", sender.getName(), target.getName(), error);
                        reply(sender, "mobs.spawn-failed");
                        return;
                    }
                    TagResolver tags = TagResolver.resolver(Msg.number("count", result.spawned()),
                            Msg.unparsed("type", spec), Msg.unparsed("player", target.getName()),
                            Msg.number("requested", (long) amount * stack.size()));
                    if (result.protectedDenied()) {
                        reply(sender, "mobs.spawn-protected", Msg.unparsed("player", target.getName()));
                    } else if (result.failed()) {
                        reply(sender, "mobs.spawn-failed");
                    } else if (result.plan().stacks() == 0) {
                        reply(sender, "mobs.chunk-full", Msg.number("cap", chunkCap));
                    } else {
                        reply(sender, switch (result.plan().limitedBy()) {
                            case NONE -> "mobs.spawned";
                            case TOTAL -> "mobs.spawned-limited-total";
                            case CHUNK -> "mobs.spawned-limited-chunk";
                        }, tags);
                    }
                });
        return Cmds.OK;
    }

    /** Region thread of {@code location}. Never loads a chunk. */
    private SpawnResult spawnAt(Location location, List<EntityType> stack, int amount, int maxTotal, int chunkCap,
            Player actor) {
        World world = location.getWorld();
        if (world == null || !location.isChunkLoaded()) {
            return new SpawnResult(0, new SpawnPlan.Plan(0, stack.size(), SpawnPlan.Limit.CHUNK), false, false);
        }
        if (protection != null && actor != null && !protection.canBuild(actor, location)) {
            return new SpawnResult(0, new SpawnPlan.Plan(0, stack.size(), SpawnPlan.Limit.CHUNK), false, true);
        }
        int existing = location.getChunk().getEntities().length;
        SpawnPlan.Plan plan = SpawnPlan.plan(amount, stack.size(), maxTotal, existing, chunkCap);
        int spawned = 0;
        for (int s = 0; s < plan.stacks(); s++) {
            Entity below = null;
            for (EntityType type : stack) {
                Entity entity;
                try {
                    entity = world.spawnEntity(location, type, CreatureSpawnEvent.SpawnReason.COMMAND);
                } catch (IllegalArgumentException e) {
                    ctx.logger().warn("Cannot spawn {} in {}", type.getKey(), world.getName(), e);
                    return new SpawnResult(spawned, plan, spawned == 0, false);
                }
                if (!entity.isValid()) {
                    break; // cancelled by another plugin: do not stack onto nothing
                }
                spawned++;
                if (below != null) {
                    below.addPassenger(entity);
                }
                below = entity;
            }
        }
        return new SpawnResult(spawned, plan, false, false);
    }

    // ---- /killall -------------------------------------------------------------------------------

    private int killall(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        MobsConfig cfg = config.get();
        CommandSender sender = c.getSource().getSender();
        String typeArg = Cmds.hasArgument(c, "type") ? StringArgumentType.getString(c, "type").toLowerCase(Locale.ROOT) : "monsters";
        Predicate<Entity> category = category(typeArg);
        if (category == null) {
            throw cmds.fail(c.getSource(), "mobs.unknown-type", Msg.unparsed("type", typeArg));
        }
        int radius;
        if (Cmds.hasArgument(c, "radius")) {
            radius = IntegerArgumentType.getInteger(c, "radius");
            if (radius > cfg.killallMaxRadius()) {
                throw cmds.fail(c.getSource(), "mobs.radius-too-large", Msg.number("max", cfg.killallMaxRadius()));
            }
        } else if (sender instanceof Player) {
            throw cmds.fail(c.getSource(), "mobs.radius-required", Msg.number("max", cfg.killallMaxRadius()));
        } else {
            radius = cfg.killallConsoleRadius();
        }
        boolean named = false;
        boolean tamed = false;
        if (Cmds.hasArgument(c, "flags")) {
            for (String flag : StringArgumentType.getString(c, "flags").toLowerCase(Locale.ROOT).split("\\s+")) {
                String f = flag.startsWith("-") ? flag.replaceFirst("^-+", "") : flag;
                if (f.equals("named") || f.equals("tamed")) {
                    if (!sender.hasPermission("atrium.killall." + f)) {
                        throw cmds.fail(c.getSource(), "mobs.no-permission-flag", Msg.unparsed("flag", f),
                                Msg.unparsed("permission", "atrium.killall." + f));
                    }
                    if (f.equals("named")) named = true; else tamed = true;
                } else if (!f.isEmpty()) {
                    throw cmds.fail(c.getSource(), "mobs.unknown-flag", Msg.unparsed("flag", flag));
                }
            }
        }
        Location center = c.getSource().getLocation();
        World world = center.getWorld();
        if (world == null) {
            world = Bukkit.getWorlds().getFirst();
            center = world.getSpawnLocation();
        }
        Predicate<Entity> filter = killFilter(category, named, tamed);
        double cx = center.getX();
        double cy = center.getY();
        double cz = center.getZ();
        double r2 = (double) radius * radius;
        World w = world;
        List<CompletableFuture<Integer>> parts = new ArrayList<>();
        for (ChunkArea.ChunkPos pos : ChunkArea.around(center.getBlockX(), center.getBlockZ(), radius)) {
            Location chunkCenter = new Location(w, (pos.x() << 4) + 8, cy, (pos.z() << 4) + 8);
            parts.add(ctx.scheduler().supplyAt(chunkCenter, () -> {
                if (!w.isChunkLoaded(pos.x(), pos.z())) {
                    return 0; // never load chunks just to empty them
                }
                int removed = 0;
                for (Entity entity : w.getChunkAt(pos.x(), pos.z()).getEntities()) {
                    Location l = entity.getLocation();
                    double dx = l.getX() - cx, dy = l.getY() - cy, dz = l.getZ() - cz;
                    if (dx * dx + dy * dy + dz * dz <= r2 && filter.test(entity)) {
                        entity.remove();
                        removed++;
                    }
                }
                return removed;
            }));
        }
        int finalRadius = radius;
        CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).whenComplete((v, error) -> {
            if (error != null) {
                ctx.logger().warn("/killall by {} failed in part", sender.getName(), error);
            }
            int total = parts.stream().filter(f -> !f.isCompletedExceptionally()).mapToInt(CompletableFuture::join).sum();
            reply(sender, error == null ? "mobs.killed" : "mobs.killed-partial", Msg.number("count", total),
                    Msg.number("radius", finalRadius), Msg.unparsed("type", typeArg));
        });
        return Cmds.OK;
    }

    private static Predicate<Entity> category(String arg) {
        return switch (arg) {
            case "monsters" -> e -> e instanceof Enemy;
            case "animals" -> e -> e instanceof Animals || e instanceof WaterMob || e instanceof Ambient;
            case "mobs" -> e -> e instanceof Mob;
            case "items" -> e -> e instanceof Item;
            case "xp" -> e -> e instanceof ExperienceOrb;
            default -> EntityTypes.parse(arg).filter(t -> t != EntityType.PLAYER)
                    .<Predicate<Entity>>map(t -> e -> e.getType() == t).orElse(null);
        };
    }

    /** Players and anything carrying a player are never removed; named/tamed only with explicit flags. */
    static Predicate<Entity> killFilter(Predicate<Entity> category, boolean named, boolean tamed) {
        return e -> {
            if (e instanceof Player || !e.isValid()) {
                return false;
            }
            for (Entity passenger : e.getPassengers()) {
                if (passenger instanceof Player) {
                    return false;
                }
            }
            if (!named && e.customName() != null) {
                return false;
            }
            if (!tamed && e instanceof Tameable t && t.isTamed()) {
                return false;
            }
            return category.test(e);
        };
    }

    // ---- /mobinfo -------------------------------------------------------------------------------

    private int mobinfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTrace(eye, eye.getDirection(), config.get().mobinfoReach(),
                FluidCollisionMode.NEVER, true, 0.2, e -> !e.equals(player));
        Entity entity = hit == null ? null : hit.getHitEntity();
        if (entity == null) {
            throw cmds.fail(c.getSource(), "mobs.no-entity");
        }
        if (!Bukkit.isOwnedByCurrentRegion(entity)) {
            throw cmds.fail(c.getSource(), "mobs.entity-elsewhere");
        }
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        Component customName = entity.customName();
        String health = "-";
        if (entity instanceof LivingEntity living) {
            var max = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            health = String.format(Locale.ROOT, "%.1f/%.1f", living.getHealth(), max == null ? living.getHealth() : max.getValue());
        }
        String owner = "-";
        if (entity instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() != null) {
            String name = tameable.getOwner().getName();
            owner = name == null ? tameable.getOwner().getUniqueId().toString() : name;
        }
        Entity vehicle = entity.getVehicle();
        ctx.messages().send(player, "mobs.info",
                Msg.unparsed("type", entity.getType().getKey().asString()),
                Msg.unparsed("uuid", entity.getUniqueId().toString()),
                Msg.unparsed("name", customName == null ? "-" : plain.serialize(customName)),
                Msg.unparsed("health", health),
                Msg.location("loc", entity.getLocation()),
                Msg.number("ticks", entity.getTicksLived()),
                Msg.unparsed("owner", owner),
                Msg.number("passengers", entity.getPassengers().size()),
                Msg.unparsed("vehicle", vehicle == null ? "-" : vehicle.getType().getKey().asString()));
        return Cmds.OK;
    }

    // ---- /setspawner ----------------------------------------------------------------------------

    private enum SpawnerResult { CHANGED, NOT_SPAWNER, DENIED, PROTECTED }

    private int spawnerUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "mobs.spawner-usage");
        return Cmds.OK;
    }

    private int setspawner(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        EntityType type = c.getArgument("type", EntityType.class);
        if (!EntityTypes.spawnerType(type)) {
            throw cmds.fail(c.getSource(), "mobs.not-spawner-type", Msg.unparsed("type", EntityTypes.name(type)));
        }
        String node = "atrium.setspawner." + EntityTypes.node(type);
        if (!player.hasPermission(node)) {
            throw cmds.fail(c.getSource(), "mobs.no-permission-type", Msg.unparsed("type", EntityTypes.name(type)),
                    Msg.unparsed("permission", node));
        }
        Location eye = player.getEyeLocation();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(eye, eye.getDirection(), config.get().spawnerReach(),
                FluidCollisionMode.NEVER, true);
        Block block = hit == null ? null : hit.getHitBlock();
        if (block == null || block.getType() != Material.SPAWNER) {
            throw cmds.fail(c.getSource(), "mobs.not-spawner");
        }
        ctx.scheduler().supplyAt(block.getLocation(), () -> {
            if (block.getType() != Material.SPAWNER || !(block.getState() instanceof CreatureSpawner spawner)) {
                return SpawnerResult.NOT_SPAWNER;
            }
            if (protection != null && !protection.canBuild(player, block.getLocation())) {
                return SpawnerResult.PROTECTED;
            }
            var event = new SpawnerChangeEvent(player, block, spawner.getSpawnedType(), type);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return SpawnerResult.DENIED;
            }
            spawner.setSpawnedType(type);
            spawner.update(true, false);
            return SpawnerResult.CHANGED;
        }).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().warn("/setspawner by {} failed", player.getName(), error);
                reply(player, "mobs.spawner-failed");
                return;
            }
            reply(player, switch (result) {
                case CHANGED -> "mobs.spawner-set";
                case NOT_SPAWNER -> "mobs.not-spawner";
                case DENIED -> "mobs.spawner-denied";
                case PROTECTED -> "mobs.spawner-protected";
            }, Msg.unparsed("type", EntityTypes.name(type)));
        });
        return Cmds.OK;
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }
}
