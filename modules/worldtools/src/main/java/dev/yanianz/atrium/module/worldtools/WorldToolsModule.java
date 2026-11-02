package dev.yanianz.atrium.module.worldtools;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.util.BiomeSearchResult;

/** /replaceblock (chunk-batched, region-threaded) and /findbiome (asynchronous biome-source search). */
public final class WorldToolsModule implements AtriumModule {
    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<WorldToolsConfig> config;
    private ProtectionService protection;
    private ReplaceJobs jobs;
    private final Set<UUID> searching = ConcurrentHashMap.newKeySet();
    private final Selections selections = new Selections();
    private volatile List<String> blockNames = List.of();

    static final String PERM_SELECT = "atrium.select";

    @Override
    public String id() {
        return "worldtools";
    }

    @Override
    public String description() {
        return "Bulk block replacement and biome search";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("integrations");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(WorldToolsConfig.class, WorldToolsConfig::new);
        this.protection = ctx.services().get(ProtectionService.class).orElse(null);
        this.jobs = new ReplaceJobs(ctx, config::get, protection);
        // The block registry only holds modern blocks (Material.values() would initialise legacy support).
        this.blockNames = RegistryAccess.registryAccess().getRegistry(RegistryKey.BLOCK).stream()
                .map(b -> b.getKey().getKey()).sorted().toList();

        ctx.permission("atrium.replaceblock", PermissionDefault.OP, "Replace blocks in a radius around you");
        ctx.permission("atrium.replaceblock.others", PermissionDefault.OP, "Cancel every running /replaceblock job");
        ctx.permission("atrium.findbiome", PermissionDefault.OP, "Find the nearest biome of a type");
        ctx.permission(PERM_SELECT, PermissionDefault.OP, "Use the selection wand and /select");

        ctx.listen(new SelectionListener(ctx, config, selections));

        SuggestionProvider<CommandSourceStack> blocks = Cmds.suggest(() -> blockNames);
        SuggestionProvider<CommandSourceStack> targets = (c, builder) -> {
            String typed = builder.getRemainingLowerCase();
            int split = Math.max(typed.lastIndexOf('+'), typed.lastIndexOf(','));
            String prefix = typed.substring(0, split + 1);
            String last = typed.substring(split + 1);
            for (String name : blockNames) {
                if (name.startsWith(last)) {
                    builder.suggest(prefix + name);
                }
            }
            return builder.buildFuture();
        };
        ctx.command(CommandSpec.of(Cmds.literal("replaceblock", "atrium.replaceblock")
                .executes(c -> usage(c.getSource().getSender()))
                .then(Cmds.literal("cancel")
                        .executes(this::cancel)
                        .then(Cmds.literal("all", "atrium.replaceblock.others").executes(this::cancelAll)))
                .then(Commands.argument("from", StringArgumentType.word()).suggests(blocks)
                        .then(Commands.argument("to", StringArgumentType.string()).suggests(targets)
                                .executes(this::replace)
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1)).executes(this::replace))
                                .then(Cmds.literal("selection").executes(this::replaceSelection))))
                .build(), "Replace one block type around you in chunk batches"));

        ctx.command(CommandSpec.of(Cmds.literal("select", PERM_SELECT)
                .executes(this::selectInfo)
                .then(Cmds.literal("pos1").executes(c -> selectCorner(c, 1)))
                .then(Cmds.literal("pos2").executes(c -> selectCorner(c, 2)))
                .then(Cmds.literal("info").executes(this::selectInfo))
                .then(Cmds.literal("clear").executes(this::selectClear))
                .then(Cmds.literal("wand").executes(this::selectWand))
                .then(Cmds.literal("expand")
                        .executes(this::selectExpandUsage)
                        .then(Commands.argument("amount", IntegerArgumentType.integer(1))
                                .suggests(Cmds.suggest(() -> List.of("1", "5", "10", "50")))
                                .executes(c -> selectExpand(c, null))
                                .then(Commands.argument("direction", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("up", "down", "north", "south", "east",
                                                "west", "vert")))
                                        .executes(c -> selectExpand(c, StringArgumentType.getString(c, "direction"))))))
                .build(), "Select two corners and use the selection with /replaceblock", "selection"));

        ctx.command(CommandSpec.of(Cmds.literal("findbiome", "atrium.findbiome")
                .executes(this::biomeUsage)
                .then(Commands.argument("biome", ArgumentTypes.resource(RegistryKey.BIOME)).executes(this::findBiome)
                        .then(Commands.argument("radius", IntegerArgumentType.integer(64)).executes(this::findBiome)))
                .build(), "Find the nearest biome of a type"));
    }

    @Override
    public void disable() {
        if (jobs != null) {
            jobs.shutdown();
        }
    }

    private int usage(CommandSender sender) {
        ctx.messages().send(sender, "worldtools.replace-usage", Msg.number("max", config.get().maxReplaceRadius()));
        return Cmds.OK;
    }

    private int biomeUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "worldtools.biome-usage");
        return Cmds.OK;
    }

    private static Optional<Material> block(String name) {
        Material material = Material.matchMaterial(name);
        return material != null && material.isBlock() ? Optional.of(material) : Optional.empty();
    }

    /** Players replace around themselves; the console and command blocks around their command location. */
    private int replace(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        WorldToolsConfig cfg = config.get();
        String fromName = StringArgumentType.getString(c, "from").toLowerCase(Locale.ROOT);
        Material from = block(fromName).orElseThrow(() -> cmds.fail(c.getSource(), "worldtools.unknown-block",
                Msg.unparsed("block", fromName)));
        String spec = StringArgumentType.getString(c, "to");
        List<Replacement.Choice<Material>> to;
        try {
            to = Replacement.parse(spec, WorldToolsModule::block);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "worldtools.bad-replacement", Msg.unparsed("spec", spec));
        }
        int radius = Cmds.hasArgument(c, "radius") ? IntegerArgumentType.getInteger(c, "radius") : cfg.defaultReplaceRadius();
        if (radius > cfg.maxReplaceRadius()) {
            throw cmds.fail(c.getSource(), "worldtools.radius-too-large", Msg.number("max", cfg.maxReplaceRadius()));
        }
        Location at = sender instanceof Player player ? player.getLocation() : c.getSource().getLocation();
        World world = at.getWorld();
        if (world == null) {
            throw cmds.fail(c.getSource(), "core.player-only");
        }
        UUID owner = sender instanceof Player player ? player.getUniqueId() : ReplaceJobs.CONSOLE;
        int bx = at.getBlockX();
        int by = at.getBlockY();
        int bz = at.getBlockZ();
        var area = new ReplaceJobs.Area(world, bx, bz, bx - radius, bx + radius,
                Math.max(world.getMinHeight(), by - radius), Math.min(world.getMaxHeight() - 1, by + radius),
                bz - radius, bz + radius);
        switch (jobs.start(owner, sender.getName(), area, from, to)) {
            case ALREADY_RUNNING -> throw cmds.fail(c.getSource(), "worldtools.replace-running");
            case TOO_MANY -> throw cmds.fail(c.getSource(), "worldtools.replace-busy");
            case STARTED -> ctx.messages().send(sender, "worldtools.replace-started",
                    Msg.unparsed("from", from.getKey().getKey()), Msg.unparsed("to", ReplaceJobs.describe(to)),
                    Msg.number("radius", radius),
                    Msg.number("chunks", jobs.job(owner).map(ReplaceJobs.Job::total).orElse(0)));
        }
        return Cmds.OK;
    }

    // ---- selection ----

    private int selectInfo(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        selections.selection(player.getUniqueId()).ifPresentOrElse(cuboid -> {
            if (!cuboid.fits(config.get().maxSelectionVolume())) {
                ctx.messages().send(player, "worldtools.select-too-big", Msg.number("volume", cuboid.volume()),
                        Msg.number("max", config.get().maxSelectionVolume()));
            }
            ctx.messages().send(player, "worldtools.select-info", Msg.unparsed("world", cuboid.world()),
                    Msg.number("min_x", cuboid.minX()), Msg.number("min_y", cuboid.minY()),
                    Msg.number("min_z", cuboid.minZ()), Msg.number("max_x", cuboid.maxX()),
                    Msg.number("max_y", cuboid.maxY()), Msg.number("max_z", cuboid.maxZ()),
                    Msg.number("volume", cuboid.volume()));
        }, () -> ctx.messages().send(player, "worldtools.select-empty",
                Msg.number("max", config.get().maxSelectionVolume())));
        return Cmds.OK;
    }

    private int selectCorner(CommandContext<CommandSourceStack> c, int corner) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Block block = player.getTargetBlockExact(Math.clamp(config.get().selectionReach(), 1, 64));
        if (block == null) {
            ctx.messages().send(player, "worldtools.select-no-block");
            return Cmds.OK;
        }
        Cuboid.Point point = new Cuboid.Point(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        selections.setCorner(player.getUniqueId(), corner, point);
        ctx.messages().send(player, "worldtools.select-corner", Msg.number("corner", corner),
                Msg.number("x", point.x()), Msg.number("y", point.y()), Msg.number("z", point.z()));
        return Cmds.OK;
    }

    private int selectClear(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        selections.clear(player.getUniqueId());
        ctx.messages().send(player, "worldtools.select-cleared");
        return Cmds.OK;
    }

    private int selectWand(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        if (!config.get().selectionWandEnabled()) {
            ctx.messages().send(player, "worldtools.select-wand-disabled");
            return Cmds.OK;
        }
        var leftover = player.getInventory().addItem(SelectionListener.wand(config.get()));
        if (!leftover.isEmpty()) {
            // Never drop a plain tool on the ground silently: report that the inventory is full.
            ctx.messages().send(player, "worldtools.select-wand-full");
            return Cmds.OK;
        }
        ctx.messages().send(player, "worldtools.select-wand-given",
                Msg.unparsed("item", config.get().selectionWandMaterial().getKey().getKey()));
        return Cmds.OK;
    }

    private int selectExpandUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "worldtools.select-expand-usage");
        return Cmds.OK;
    }

    private int selectExpand(CommandContext<CommandSourceStack> c, @org.jspecify.annotations.Nullable String directionName) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        Cuboid current = selections.selection(player.getUniqueId()).orElse(null);
        if (current == null) {
            ctx.messages().send(player, "worldtools.select-empty", Msg.number("max", config.get().maxSelectionVolume()));
            return Cmds.OK;
        }
        Cuboid.Direction direction = directionName == null
                ? Cuboid.facing(player.getLocation().getYaw(), player.getLocation().getPitch())
                : Cuboid.Direction.parse(directionName);
        if (direction == null) {
            ctx.messages().send(player, "worldtools.select-bad-direction", Msg.unparsed("direction", directionName));
            return Cmds.OK;
        }
        int amount = IntegerArgumentType.getInteger(c, "amount");
        org.bukkit.World world = player.getWorld();
        Cuboid expanded = current.expand(direction, amount, world.getMinHeight(), world.getMaxHeight() - 1);
        if (!expanded.fits(config.get().maxSelectionVolume())) {
            ctx.messages().send(player, "worldtools.select-too-big", Msg.number("volume", expanded.volume()),
                    Msg.number("max", config.get().maxSelectionVolume()));
            return Cmds.OK;
        }
        selections.setSelection(player.getUniqueId(), expanded);
        ctx.messages().send(player, "worldtools.select-expanded",
                Msg.unparsed("direction", direction.name().toLowerCase(Locale.ROOT)),
                Msg.number("volume", expanded.volume()));
        return Cmds.OK;
    }

    /** Starts a job on the selection; the area is copied now so a later /select does not change a running job. */
    private int replaceSelection(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        if (!(sender instanceof Player player)) {
            throw cmds.fail(c.getSource(), "core.player-only");
        }
        WorldToolsConfig cfg = config.get();
        Cuboid cuboid = selections.selection(player.getUniqueId()).orElseThrow(() ->
                cmds.fail(c.getSource(), "worldtools.select-empty", Msg.number("max", cfg.maxSelectionVolume())));
        if (!cuboid.fits(cfg.maxSelectionVolume())) {
            throw cmds.fail(c.getSource(), "worldtools.select-too-big", Msg.number("volume", cuboid.volume()),
                    Msg.number("max", cfg.maxSelectionVolume()));
        }
        World world = Bukkit.getWorld(cuboid.world());
        if (world == null) {
            throw cmds.fail(c.getSource(), "worldtools.select-world-gone", Msg.unparsed("world", cuboid.world()));
        }
        Material from = block(StringArgumentType.getString(c, "from").toLowerCase(Locale.ROOT)).orElseThrow(() ->
                cmds.fail(c.getSource(), "worldtools.unknown-block",
                        Msg.unparsed("block", StringArgumentType.getString(c, "from"))));
        String spec = StringArgumentType.getString(c, "to");
        List<Replacement.Choice<Material>> to;
        try {
            to = Replacement.parse(spec, WorldToolsModule::block);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "worldtools.bad-replacement", Msg.unparsed("spec", spec));
        }
        var area = new ReplaceJobs.Area(world, (cuboid.minX() + cuboid.maxX()) / 2, (cuboid.minZ() + cuboid.maxZ()) / 2,
                cuboid.minX(), cuboid.maxX(), cuboid.minY(), cuboid.maxY(), cuboid.minZ(), cuboid.maxZ());
        switch (jobs.start(player.getUniqueId(), player.getName(), area, from, to)) {
            case ALREADY_RUNNING -> throw cmds.fail(c.getSource(), "worldtools.replace-running");
            case TOO_MANY -> throw cmds.fail(c.getSource(), "worldtools.replace-busy");
            case STARTED -> ctx.messages().send(sender, "worldtools.replace-started-selection",
                    Msg.unparsed("from", from.getKey().getKey()), Msg.unparsed("to", ReplaceJobs.describe(to)),
                    Msg.number("volume", cuboid.volume()),
                    Msg.number("chunks", jobs.job(player.getUniqueId()).map(ReplaceJobs.Job::total).orElse(0)));
        }
        return Cmds.OK;
    }

    private int cancel(CommandContext<CommandSourceStack> c) {        CommandSender sender = c.getSource().getSender();
        boolean cancelled = jobs.cancel(sender instanceof Player player ? player.getUniqueId() : ReplaceJobs.CONSOLE);
        ctx.messages().send(sender, cancelled ? "worldtools.replace-cancelling" : "worldtools.replace-none");
        return Cmds.OK;
    }

    private int cancelAll(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "worldtools.replace-cancelled-all", Msg.number("count", jobs.cancelAll()));
        return Cmds.OK;
    }

    private int findBiome(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        WorldToolsConfig cfg = config.get();
        Biome biome = c.getArgument("biome", Biome.class);
        int radius = Cmds.hasArgument(c, "radius") ? IntegerArgumentType.getInteger(c, "radius") : cfg.defaultBiomeRadius();
        if (radius > cfg.maxBiomeRadius()) {
            throw cmds.fail(c.getSource(), "worldtools.radius-too-large", Msg.number("max", cfg.maxBiomeRadius()));
        }
        if (!searching.add(player.getUniqueId())) {
            throw cmds.fail(c.getSource(), "worldtools.biome-busy");
        }
        Location origin = player.getLocation();
        World world = origin.getWorld();
        String biomeName = biome.getKey().asString();
        ctx.messages().send(player, "worldtools.biome-searching", Msg.unparsed("biome", biomeName), Msg.number("radius", radius));
        try {
            // The search only samples the world's biome source (noise), so it does not touch chunks and can run
            // off the region threads. If the server refuses an off-thread call, report it as unsupported.
            ctx.track(ctx.scheduler().async(() -> {
                try {
                    BiomeSearchResult result = world.locateNearestBiome(origin, radius, 32, 64, biome);
                    if (result == null) {
                        reply(player, "worldtools.biome-not-found", Msg.unparsed("biome", biomeName), Msg.number("radius", radius));
                    } else {
                        Location found = result.getLocation();
                        reply(player, "worldtools.biome-found", Msg.unparsed("biome", biomeName), Msg.location("loc", found),
                                Msg.number("distance", Math.round(Math.hypot(found.getX() - origin.getX(), found.getZ() - origin.getZ()))));
                    }
                } catch (UnsupportedOperationException | IllegalStateException e) {
                    ctx.logger().info("World#locateNearestBiome is not available off-thread here: {}", e.toString());
                    reply(player, "worldtools.biome-unsupported");
                } catch (RuntimeException e) {
                    ctx.logger().warn("/findbiome {} for {} failed", biomeName, player.getName(), e);
                    reply(player, "worldtools.biome-failed");
                } finally {
                    searching.remove(player.getUniqueId());
                }
            }));
        } catch (RuntimeException e) {
            searching.remove(player.getUniqueId());
            throw e;
        }
        return Cmds.OK;
    }

    private void reply(Player player, String key, TagResolver... tags) {
        ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
    }
}
