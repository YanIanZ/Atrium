package dev.yanianz.atrium.module.search;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Command tree of the search module. Behaviour lives in {@link ItemSearches} and the scan jobs. */
final class SearchCommands {
    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<SearchConfig> config;
    private final ItemSearches searches;
    private final Scans scans;
    private final List<String> materialNames;

    SearchCommands(ModuleContext ctx, ConfigHandle<SearchConfig> config, ItemSearches searches, Scans scans) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.searches = searches;
        this.scans = scans;
        // Only modern item names; Material.values() would initialise the legacy material support.
        this.materialNames = RegistryAccess.registryAccess().getRegistry(RegistryKey.ITEM).stream()
                .map(item -> item.getKey().getKey())
                .sorted()
                .toList();
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("search", "atrium.search")
                .executes(this::usage)
                .then(Cmds.literal("item", "atrium.search.item")
                        .executes(this::usage)
                        .then(Commands.argument("material", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> materialNames))
                                .executes(c -> item(c, null))
                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                        .suggests(options())
                                        .executes(c -> item(c, StringArgumentType.getString(c, "options"))))))
                .then(Cmds.literal("results", "atrium.search.results")
                        .executes(c -> results(c, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(c -> results(c, IntegerArgumentType.getInteger(c, "page")))))
                .then(Cmds.literal("cancel", "atrium.search.cancel").executes(this::cancel))
                .build(), "Search online players' inventories for an item"));

        ctx.command(CommandSpec.of(Cmds.literal("scan", "atrium.scan")
                .executes(this::scanUsage)
                .then(Cmds.literal("containers", "atrium.scan.containers")
                        .executes(this::scanUsage)
                        .then(Commands.argument("material", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> materialNames))
                                .executes(this::containers)
                                .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                        .executes(this::containers))))
                .then(Cmds.literal("entities", "atrium.scan.entities")
                        .executes(c -> entities(c, null))
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(Cmds.suggest(() -> Bukkit.getWorlds().stream()
                                        .map(World::getName).toList()))
                                .executes(c -> entities(c, StringArgumentType.getString(c, "world")))))
                .then(Cmds.literal("cancel", "atrium.scan.cancel")
                        .executes(this::cancelScan)
                        .then(Cmds.literal("all", "atrium.scan.cancel.all").executes(this::cancelAllScans)))
                .build(), "Scan loaded chunks for containers or busy entities"));
    }

    // ---- /search item ---------------------------------------------------------------------------

    private int usage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "search.usage");
        return Cmds.OK;
    }

    private int scanUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "search.scan-usage",
                Msg.number("max", config.get().maxScanRadius()));
        return Cmds.OK;
    }

    private int item(CommandContext<CommandSourceStack> c, String rawOptions) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String materialName = StringArgumentType.getString(c, "material");
        ItemFilter.Options options;
        try {
            options = ItemFilter.parseOptions(rawOptions);
        } catch (IllegalArgumentException e) {
            throw cmds.fail(c.getSource(), "search.invalid-option", Msg.unparsed("reason", e.getMessage()));
        }
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            throw cmds.fail(c.getSource(), "search.invalid-item", Msg.unparsed("item", materialName));
        }
        Player only = null;
        if (options.player() != null) {
            only = Bukkit.getPlayerExact(options.player());
            if (only == null) {
                throw cmds.fail(c.getSource(), "search.player-not-online",
                        Msg.unparsed("player", options.player()));
            }
        }
        ItemFilter filter = new ItemFilter(material, options.name(), options.customModelData());
        switch (searches.start(sender, filter, only)) {
            case ALREADY_RUNNING -> throw cmds.fail(c.getSource(), "search.busy");
            case STARTED -> {
                // The started (or "no players") message is sent by ItemSearches.
            }
        }
        return Cmds.OK;
    }

    private int results(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = c.getSource().getSender();
        SearchReport report = searches.report(Scans.owner(sender));
        if (report == null) {
            ctx.messages().send(sender, "search.results-expired");
            return Cmds.OK;
        }
        report.send(ctx, config.get(), Scans.owner(sender), page);
        return Cmds.OK;
    }

    private int cancel(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        boolean cancelled = searches.cancel(Scans.owner(sender));
        ctx.messages().send(sender, cancelled ? "search.cancelled-requested" : "search.cancel-none");
        return Cmds.OK;
    }

    // ---- /scan containers -----------------------------------------------------------------------

    private int containers(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        SearchConfig cfg = config.get();
        String materialName = StringArgumentType.getString(c, "material");
        Material material = Material.matchMaterial(materialName);
        if (material == null || !material.isItem()) {
            throw cmds.fail(c.getSource(), "search.invalid-item", Msg.unparsed("item", materialName));
        }
        int radius = Cmds.hasArgument(c, "radius") ? IntegerArgumentType.getInteger(c, "radius")
                : cfg.defaultScanRadius();
        if (radius > cfg.maxScanRadius()) {
            throw cmds.fail(c.getSource(), "search.containers-radius", Msg.number("max", cfg.maxScanRadius()));
        }
        Location at = sender instanceof Player player ? player.getLocation() : c.getSource().getLocation();
        if (at == null || at.getWorld() == null) {
            throw cmds.fail(c.getSource(), "core.player-only");
        }
        UUID owner = Scans.owner(sender);
        ContainerScan job = new ContainerScan(ctx, scans, config::get, owner, sender.getName(), at.getWorld(),
                at.getBlockX() >> 4, at.getBlockZ() >> 4, radius, material);
        if (!scans.start(job)) {
            throw cmds.fail(c.getSource(), "search.scan-busy");
        }
        ctx.messages().send(sender, "search.containers-started",
                Msg.unparsed("item", material.getKey().getKey()),
                Msg.number("chunks", job.total()),
                Msg.number("x", at.getBlockX()),
                Msg.number("z", at.getBlockZ()),
                Msg.number("radius", radius));
        return Cmds.OK;
    }

    // ---- /scan entities -------------------------------------------------------------------------

    private int entities(CommandContext<CommandSourceStack> c, String worldName) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        World world = null;
        if (worldName != null) {
            world = Bukkit.getWorld(worldName);
            if (world == null) {
                throw cmds.fail(c.getSource(), "search.entities-unknown-world", Msg.unparsed("world", worldName));
            }
        }
        EntityScan job = new EntityScan(ctx, scans, config::get, Scans.owner(sender), sender.getName(), world);
        if (!scans.start(job)) {
            throw cmds.fail(c.getSource(), "search.scan-busy");
        }
        return Cmds.OK;
    }

    // ---- /scan cancel ---------------------------------------------------------------------------

    private int cancelScan(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        boolean cancelled = scans.cancel(Scans.owner(sender));
        ctx.messages().send(sender, cancelled ? "search.scan-cancel-requested" : "search.scan-cancel-none");
        return Cmds.OK;
    }

    private int cancelAllScans(CommandContext<CommandSourceStack> c) {
        int count = scans.cancelAll();
        ctx.messages().send(c.getSource().getSender(), "search.scan-cancel-all", Msg.number("count", count));
        return Cmds.OK;
    }

    /** Suggests the option keys and online names after {@code player:}. */
    private static SuggestionProvider<CommandSourceStack> options() {
        return (c, builder) -> {
            String remaining = builder.getRemaining();
            int space = remaining.lastIndexOf(' ');
            String prefix = space >= 0 ? remaining.substring(0, space + 1) : "";
            String last = remaining.substring(space + 1).toLowerCase(Locale.ROOT);
            if (last.startsWith("player:")) {
                String namePart = last.substring("player:".length());
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getName().toLowerCase(Locale.ROOT).startsWith(namePart)) {
                        builder.suggest(prefix + "player:" + player.getName());
                    }
                }
            } else {
                for (String key : List.of("name:", "cmd:", "player:")) {
                    if (key.startsWith(last)) {
                        builder.suggest(prefix + key);
                    }
                }
            }
            return builder.buildFuture();
        };
    }
}
