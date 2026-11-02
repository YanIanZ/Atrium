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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
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
    private volatile List<String> blockNames = List.of();

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
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1)).executes(this::replace))))
                .build(), "Replace one block type around you in chunk batches"));

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

    private int cancel(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
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
