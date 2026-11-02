package dev.yanianz.atrium.module.worlds;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.yanianz.atrium.api.profile.ActiveProfileService;
import dev.yanianz.atrium.api.profile.ProfileWorldService;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldProvider.UnloadResult;
import dev.yanianz.atrium.api.world.WorldProviderRegistry.Match;
import dev.yanianz.atrium.api.world.WorldResolver.Resolution;
import dev.yanianz.atrium.api.world.WorldResolver.Status;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** {@code /wtp}, {@code /wtp profile}, {@code /worlds} (list and unload) and {@code /worldinfo}. */
final class WorldCommands {
    static final String PERM_WORLDS = "atrium.worlds";
    static final String PERM_UNLOAD = "atrium.worlds.unload";
    static final String PERM_LOAD = "atrium.worlds.load";
    static final String PERM_INFO = "atrium.worldinfo";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final ConfigHandle<WorldsConfig> config;
    private final WorldProviderChain chain;
    private final UnloadConfirmations confirmations = new UnloadConfirmations(System::currentTimeMillis);

    WorldCommands(ModuleContext ctx, ConfigHandle<WorldsConfig> config, WorldProviderChain chain) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = config;
        this.chain = chain;
    }

    void register(boolean mvtpAlias) {
        LiteralArgumentBuilder<CommandSourceStack> wtp = Commands.literal("wtp")
                .requires(source -> source.getSender().hasPermission(WorldAccess.PERM_WTP) || profileCommandUsable(source))
                .executes(c -> usage(c, "worlds.usage-wtp"))
                .then(Commands.literal("profile")
                        .requires(this::profileCommandUsable)
                        .executes(this::profile)
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(this::profile)))
                .then(Commands.argument("world", StringArgumentType.word())
                        .requires(Cmds.permission(WorldAccess.PERM_WTP))
                        .suggests(this::suggestWorlds)
                        .executes(this::wtp)
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(this::wtp)));
        String[] aliases = mvtpAlias ? new String[] {"worldtp", "mvtp"} : new String[] {"worldtp"};
        ctx.command(CommandSpec.of(wtp.build(), "Teleport to a world (loads it first when needed)", aliases));

        RequiredArgumentBuilder<CommandSourceStack, String> unloadWorld = Commands.argument("world", StringArgumentType.word())
                .suggests(this::suggestUnloadable)
                .executes(c -> unload(c, false))
                .then(Commands.literal("confirm").executes(c -> unload(c, true)));
        ctx.command(CommandSpec.of(Cmds.literal("worlds", PERM_WORLDS)
                .executes(c -> list(c, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(c -> list(c, IntegerArgumentType.getInteger(c, "page"))))
                .then(Cmds.literal("load", PERM_LOAD)
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(this::suggestUnloaded)
                                .executes(this::load)))
                .then(Cmds.literal("unload", PERM_UNLOAD).then(unloadWorld))
                .build(), "List worlds of every world provider"));

        ctx.command(CommandSpec.of(Cmds.literal("worldinfo", PERM_INFO)
                .executes(this::info)
                .then(Commands.argument("world", StringArgumentType.word())
                        .suggests(this::suggestAllWorlds)
                        .executes(this::info))
                .build(), "Show the provider, state, spawn and players of a world"));
    }

    void clear() {
        confirmations.clear();
    }

    private WorldsConfig cfg() {
        return config.get();
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        ctx.messages().send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    // ---- suggestions ----

    private CompletableFuture<Suggestions> suggestWorlds(CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        CommandSender sender = c.getSource().getSender();
        boolean require = cfg().requireWorldPermission;
        WorldAccess.Perms perms = WorldAccess.of(sender);
        for (String name : WorldSuggestions.filter(chain.worlds(), builder.getRemaining(),
                world -> WorldAccess.mayUse(perms, world, require))) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestAllWorlds(CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        for (String name : WorldSuggestions.filter(chain.worlds(), builder.getRemaining(), world -> true)) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestUnloadable(CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        List<KnownWorld> unloadable = chain.worlds().stream()
                .filter(world -> world.loaded() && unloadableProvider(world.provider()))
                .toList();
        for (String name : WorldSuggestions.filter(unloadable, builder.getRemaining(), world -> true)) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestUnloaded(CommandContext<CommandSourceStack> c, SuggestionsBuilder builder) {
        List<KnownWorld> unloaded = chain.worlds().stream()
                .filter(world -> !world.loaded() && Bukkit.getWorld(world.name()) == null)
                .toList();
        for (String name : WorldSuggestions.filter(unloaded, builder.getRemaining(), world -> true)) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static boolean unloadableProvider(String id) {
        return WorldProvider.MULTIVERSE.equals(id) || WorldProvider.SLIME.equals(id);
    }

    // ---- /wtp <world> [player] ----

    private int wtp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        String input = StringArgumentType.getString(c, "world");
        Player target = cmds.selfOrOther(c, "player", WorldAccess.PERM_OTHERS);
        if (!WorldNames.valid(input)) {
            throw cmds.fail(source, "worlds.invalid-name", Msg.unparsed("world", input));
        }
        Match match = chain.find(input).orElseThrow(() -> cmds.fail(source, "worlds.unknown", Msg.unparsed("world", input)));
        String world = match.world();
        if (!WorldAccess.mayUse(WorldAccess.of(sender), world, cfg().requireWorldPermission)) {
            throw cmds.fail(source, "worlds.no-permission-world", Msg.unparsed("world", world),
                    Msg.unparsed("permission", WorldAccess.worldNode(world)));
        }
        if (!mayEnter(match.provider(), target, world)) {
            throw cmds.fail(source, "worlds.no-access", Msg.unparsed("world", world),
                    Msg.unparsed("player", target.getName()), Msg.unparsed("provider", match.provider().displayName()));
        }
        travel(sender, target, match.provider(), world);
        return Cmds.OK;
    }

    private boolean mayEnter(WorldProvider provider, Player player, String world) {
        try {
            return provider.mayEnter(player, world);
        } catch (RuntimeException | LinkageError e) {
            ctx.logger().warn("World provider {} failed to check access of {} to {}", provider.id(), player.getName(), world, e);
            return false;
        }
    }

    /** Loads the world when needed (with a progress message), then teleports to the provider's spawn. */
    private void travel(CommandSender sender, Player target, WorldProvider provider, String world) {
        CompletableFuture<Resolution> lookup;
        try {
            lookup = chain.load(provider, world);
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        if (!lookup.isDone()) {
            reply(sender, "worlds.loading", Msg.unparsed("world", world), Msg.unparsed("provider", provider.displayName()));
        }
        lookup.whenComplete((resolution, error) -> {
            if (error != null || resolution == null) {
                ctx.logger().warn("Resolving world {} for {} failed", world, target.getName(), error);
                reply(sender, "worlds.load-failed", Msg.unparsed("world", world),
                        Msg.unparsed("detail", error == null ? "no result" : String.valueOf(error.getMessage())));
                return;
            }
            if (!resolution.available()) {
                reply(sender, statusKey(resolution.status()), Msg.unparsed("world", world),
                        Msg.unparsed("detail", resolution.detail()));
                return;
            }
            World loaded = resolution.world();
            teleport(sender, target, spawnOf(provider, loaded), loaded.getName());
        });
    }

    static String statusKey(Status status) {
        return switch (status) {
            case NOT_FOUND -> "worlds.unknown";
            case NOT_ALLOWED -> "worlds.load-not-allowed";
            case BUSY -> "worlds.load-busy";
            case TIMEOUT -> "worlds.load-timeout";
            default -> "worlds.load-failed";
        };
    }

    private Location spawnOf(WorldProvider provider, World world) {
        try {
            Optional<Location> spawn = provider.spawn(world);
            if (spawn.isPresent() && spawn.get().getWorld() != null) {
                return spawn.get();
            }
        } catch (RuntimeException | LinkageError e) {
            ctx.logger().warn("World provider {} failed to give the spawn of {}", provider.id(), world.getName(), e);
        }
        return world.getSpawnLocation();
    }

    private void teleport(CommandSender sender, Player target, Location spawn, String world) {
        boolean self = sender instanceof Player player && player.getUniqueId().equals(target.getUniqueId());
        CompletableFuture<TeleportResult> move;
        Optional<ExtendedTeleportService> extended = ctx.services().get(ExtendedTeleportService.class);
        Optional<TeleportService> basic = ctx.services().get(TeleportService.class);
        try {
            if (extended.isPresent()) {
                // Another player is moved at once (a warmup would wait for them); safety checks and /back stay.
                move = extended.get().teleport(target, spawn, TeleportCause.COMMAND,
                        self ? TeleportOptions.DEFAULT : TeleportOptions.IMMEDIATE);
            } else if (basic.isPresent()) {
                move = self ? basic.get().teleport(target, spawn, TeleportCause.COMMAND)
                        : basic.get().teleportNow(target, spawn, TeleportCause.COMMAND);
            } else {
                move = ctx.scheduler().supplyOnEntity(target, () -> target.teleportAsync(spawn))
                        .thenCompose(future -> future)
                        .thenApply(ok -> Boolean.TRUE.equals(ok) ? TeleportResult.SUCCESS : TeleportResult.FAILED);
            }
        } catch (RuntimeException e) {
            move = CompletableFuture.failedFuture(e);
        }
        move.whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().warn("Teleport of {} to world {} failed", target.getName(), world, error);
            }
            if (error == null && result == TeleportResult.SUCCESS) {
                reply(target, "worlds.teleported", Msg.unparsed("world", world));
                if (!self) {
                    reply(sender, "worlds.teleported-other", Msg.unparsed("world", world), Msg.unparsed("player", target.getName()));
                }
            } else if (!self || (extended.isEmpty() && basic.isEmpty())) {
                reply(sender, "worlds.teleport-failed", Msg.unparsed("world", world), Msg.unparsed("player", target.getName()),
                        Msg.unparsed("reason", error != null ? "error" : String.valueOf(result).toLowerCase(Locale.ROOT)));
            }
        });
    }

    /** Sends on the receiver's own thread (players) or directly (console). */
    private void reply(CommandSender to, String key, TagResolver... resolvers) {
        if (to instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, resolvers), null);
        } else {
            ctx.messages().send(to, key, resolvers);
        }
    }

    // ---- /wtp profile [player] ----

    private boolean profileCommandUsable(CommandSourceStack source) {
        return source.getSender().hasPermission(WorldAccess.PERM_PROFILE) && profileService().isPresent();
    }

    private Optional<ProfileWorldService> profileService() {
        return ctx.services().get(ProfileWorldService.class).filter(service -> {
            try {
                return service.enabled();
            } catch (RuntimeException | LinkageError e) {
                return false;
            }
        });
    }

    private int profile(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        Player target = cmds.selfOrOther(c, "player", WorldAccess.PERM_OTHERS);
        ProfileWorldService service = profileService()
                .orElseThrow(() -> cmds.fail(source, "worlds.profile-unavailable"));
        WorldProvider awf = chain.provider(WorldProvider.AWF)
                .orElseThrow(() -> cmds.fail(source, "worlds.profile-no-awf"));
        String profileName = ctx.services().get(ActiveProfileService.class)
                .flatMap(active -> active.cached(target.getUniqueId()))
                .map(ActiveProfileService.ActiveProfile::name)
                .filter(name -> !name.isBlank())
                .orElse("");
        CompletableFuture<Optional<ProfileWorldService.ProfileWorlds>> worlds;
        try {
            worlds = service.activeProfileWorlds(target.getUniqueId());
        } catch (RuntimeException e) {
            worlds = CompletableFuture.failedFuture(e);
        }
        worlds.whenComplete((profile, error) -> {
            if (error != null) {
                ctx.logger().warn("Reading the profile worlds of {} failed", target.getName(), error);
                reply(sender, "worlds.profile-failed", Msg.unparsed("player", target.getName()));
                return;
            }
            if (profile == null || profile.isEmpty()) {
                reply(sender, "worlds.profile-none", Msg.unparsed("player", target.getName()));
                return;
            }
            Optional<ProfileWorldService.ProfileWorld> primary = profile.get().primary();
            if (primary.isEmpty() || !WorldNames.valid(primary.get().world())) {
                reply(sender, "worlds.profile-no-primary", Msg.unparsed("player", target.getName()),
                        Msg.unparsed("profile", profileName.isEmpty() ? shortId(profile.get().profileId().toString()) : profileName));
                return;
            }
            travel(sender, target, awf, primary.get().world());
        });
        return Cmds.OK;
    }

    private static String shortId(String id) {
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    // ---- /worlds [page] ----

    private int list(CommandContext<CommandSourceStack> c, int page) {
        CommandSender sender = c.getSource().getSender();
        boolean require = cfg().requireWorldPermission;
        WorldAccess.Perms perms = WorldAccess.of(sender);
        List<KnownWorld> worlds = new ArrayList<>(chain.worlds().stream()
                .filter(world -> WorldAccess.mayUse(perms, world.name(), require))
                .toList());
        worlds.sort(Comparator.comparing(KnownWorld::name, String.CASE_INSENSITIVE_ORDER));
        if (worlds.isEmpty()) {
            ctx.messages().send(sender, "worlds.list-empty");
            return Cmds.OK;
        }
        int size = Math.max(3, Math.min(50, cfg().pageSize));
        int pages = WorldSuggestions.pageCount(worlds.size(), size);
        int current = Math.max(1, Math.min(page, pages));
        Map<String, Integer> players = playerCounts();
        ctx.messages().send(sender, "worlds.list-header", Msg.number("page", current), Msg.number("pages", pages),
                Msg.number("count", worlds.size()));
        for (KnownWorld world : WorldSuggestions.page(worlds, current, size)) {
            boolean loaded = world.loaded() || Bukkit.getWorld(world.name()) != null;
            TagResolver[] tags = {
                    Msg.unparsed("world", world.name()),
                    Msg.unparsed("alias", world.hasAlias() ? world.alias() : ""),
                    Msg.unparsed("provider", providerName(world.provider())),
                    Msg.component("state", ctx.messages().render(sender, loaded ? "worlds.state-loaded" : "worlds.state-unloaded")),
                    Msg.number("players", players.getOrDefault(world.name().toLowerCase(Locale.ROOT), 0))
            };
            Component line = ctx.messages().render(sender, world.hasAlias() ? "worlds.list-entry-alias" : "worlds.list-entry", tags);
            if (WorldNames.valid(world.name())) {
                line = line.clickEvent(ClickEvent.runCommand("/wtp " + world.name()))
                        .hoverEvent(HoverEvent.showText(ctx.messages().render(sender, "worlds.list-hover", tags)));
            }
            sender.sendMessage(line);
        }
        if (current < pages) {
            Component next = ctx.messages().render(sender, "worlds.list-next", Msg.number("page", current + 1));
            sender.sendMessage(next.clickEvent(ClickEvent.runCommand("/worlds " + (current + 1))));
        }
        return Cmds.OK;
    }

    private String providerName(String id) {
        return chain.provider(id).map(WorldProvider::displayName).orElse(id);
    }

    /** Online players per world (lower-case name). Reads only each player's world reference. */
    private static Map<String, Integer> playerCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            counts.merge(player.getWorld().getName().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return counts;
    }

    // ---- /worlds load <world> ----

    private int load(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        String input = StringArgumentType.getString(c, "world");
        if (!WorldNames.valid(input)) {
            throw cmds.fail(source, "worlds.invalid-name", Msg.unparsed("world", input));
        }
        Match match = chain.find(input).orElseThrow(() -> cmds.fail(source, "worlds.unknown", Msg.unparsed("world", input)));
        String world = match.world();
        if (Bukkit.getWorld(world) != null) {
            throw cmds.fail(source, "worlds.already-loaded", Msg.unparsed("world", world));
        }
        CompletableFuture<Resolution> lookup;
        try {
            lookup = chain.load(match.provider(), world);
        } catch (RuntimeException e) {
            lookup = CompletableFuture.failedFuture(e);
        }
        if (!lookup.isDone()) {
            ctx.messages().send(sender, "worlds.loading", Msg.unparsed("world", world),
                    Msg.unparsed("provider", match.provider().displayName()));
        }
        lookup.whenComplete((resolution, error) -> {
            if (error != null || resolution == null) {
                ctx.logger().warn("Loading world {} failed", world, error);
                reply(sender, "worlds.load-failed", Msg.unparsed("world", world),
                        Msg.unparsed("detail", error == null ? "no result" : String.valueOf(error.getMessage())));
            } else if (resolution.available()) {
                reply(sender, "worlds.loaded", Msg.unparsed("world", world),
                        Msg.unparsed("provider", match.provider().displayName()));
            } else {
                reply(sender, statusKey(resolution.status()), Msg.unparsed("world", world),
                        Msg.unparsed("detail", resolution.detail()));
            }
        });
        return Cmds.OK;
    }

    // ---- /worlds unload <world> [confirm] ----

    private int unload(CommandContext<CommandSourceStack> c, boolean confirm) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        String input = StringArgumentType.getString(c, "world");
        if (!WorldNames.valid(input)) {
            throw cmds.fail(source, "worlds.invalid-name", Msg.unparsed("world", input));
        }
        Match match = chain.find(input).orElseThrow(() -> cmds.fail(source, "worlds.unknown", Msg.unparsed("world", input)));
        String world = match.world();
        WorldProvider provider = match.provider();
        World loaded = Bukkit.getWorld(world);
        if (loaded == null) {
            throw cmds.fail(source, "worlds.unload-not-loaded", Msg.unparsed("world", world));
        }
        if (!Bukkit.getWorlds().isEmpty() && Bukkit.getWorlds().getFirst().getName().equalsIgnoreCase(world)) {
            throw cmds.fail(source, "worlds.unload-main", Msg.unparsed("world", world));
        }
        boolean supported;
        try {
            supported = unloadableProvider(provider.id()) && provider.canUnload(world);
        } catch (RuntimeException | LinkageError e) {
            supported = false;
        }
        if (!supported) {
            throw cmds.fail(source, "worlds.unload-unsupported", Msg.unparsed("world", world),
                    Msg.unparsed("provider", provider.displayName()));
        }
        int players = playerCounts().getOrDefault(world.toLowerCase(Locale.ROOT), 0);
        if (players > 0) {
            throw cmds.fail(source, "worlds.unload-players", Msg.unparsed("world", world), Msg.number("players", players));
        }
        String key = sender instanceof Player player ? player.getUniqueId().toString() : "console:" + sender.getName();
        if (!confirm) {
            int seconds = Math.max(5, Math.min(300, cfg().unloadConfirmSeconds));
            confirmations.request(key, world, seconds * 1000L);
            Component line = ctx.messages().render(sender, "worlds.unload-confirm", Msg.unparsed("world", world),
                    Msg.unparsed("provider", provider.displayName()), Msg.number("seconds", seconds));
            sender.sendMessage(line.clickEvent(ClickEvent.runCommand("/worlds unload " + world + " confirm")));
            return Cmds.OK;
        }
        if (!confirmations.consume(key, world)) {
            throw cmds.fail(source, "worlds.unload-no-pending", Msg.unparsed("world", world));
        }
        CompletableFuture<UnloadResult> result;
        try {
            result = provider.unload(world);
        } catch (RuntimeException | LinkageError e) {
            result = CompletableFuture.failedFuture(e);
        }
        ctx.messages().send(sender, "worlds.unloading", Msg.unparsed("world", world));
        result.whenComplete((outcome, error) -> {
            if (error != null || outcome == null) {
                ctx.logger().warn("Unloading world {} through {} failed", world, provider.id(), error);
                reply(sender, "worlds.unload-failed", Msg.unparsed("world", world),
                        Msg.unparsed("detail", error == null ? "no result" : String.valueOf(error.getMessage())));
                return;
            }
            switch (outcome.status()) {
                case UNLOADED -> {
                    ctx.logger().info("{} unloaded world {} ({})", sender.getName(), world, provider.id());
                    reply(sender, "worlds.unloaded", Msg.unparsed("world", world));
                }
                case NOT_LOADED -> reply(sender, "worlds.unload-not-loaded", Msg.unparsed("world", world));
                case PLAYERS_PRESENT -> reply(sender, "worlds.unload-players", Msg.unparsed("world", world), Msg.number("players", 1));
                case UNSUPPORTED -> reply(sender, "worlds.unload-unsupported", Msg.unparsed("world", world),
                        Msg.unparsed("provider", provider.displayName()));
                default -> reply(sender, "worlds.unload-failed", Msg.unparsed("world", world), Msg.unparsed("detail", outcome.detail()));
            }
        });
        return Cmds.OK;
    }

    // ---- /worldinfo [world] ----

    private int info(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        CommandSender sender = source.getSender();
        String input;
        if (Cmds.hasArgument(c, "world")) {
            input = StringArgumentType.getString(c, "world");
        } else if (sender instanceof Player player) {
            input = player.getWorld().getName();
        } else {
            return usage(c, "worlds.usage-worldinfo");
        }
        Optional<Match> match = chain.find(input);
        World loaded = Bukkit.getWorld(match.map(Match::world).orElse(input));
        if (match.isEmpty() && loaded == null) {
            throw cmds.fail(source, "worlds.unknown", Msg.unparsed("world", input));
        }
        String world = loaded != null ? loaded.getName() : match.get().world();
        String providerName = match.map(m -> m.provider().displayName()).orElse("Bukkit");
        KnownWorld known = chain.worlds().stream().filter(w -> w.name().equalsIgnoreCase(world)).findFirst().orElse(null);
        ctx.messages().send(sender, "worlds.info-header", Msg.unparsed("world", world));
        ctx.messages().send(sender, "worlds.info-provider", Msg.unparsed("provider", providerName));
        if (known != null && known.hasAlias()) {
            ctx.messages().send(sender, "worlds.info-alias", Msg.unparsed("alias", known.alias()));
        }
        ctx.messages().send(sender, "worlds.info-loaded",
                Msg.component("state", ctx.messages().render(sender, loaded != null ? "worlds.state-loaded" : "worlds.state-unloaded")));
        if (loaded != null) {
            Location spawn = match.map(m -> spawnOf(m.provider(), loaded)).orElseGet(loaded::getSpawnLocation);
            ctx.messages().send(sender, "worlds.info-spawn", Msg.location("at", spawn));
            ctx.messages().send(sender, "worlds.info-environment",
                    Msg.unparsed("environment", loaded.getEnvironment().name().toLowerCase(Locale.ROOT)));
            ctx.messages().send(sender, "worlds.info-players",
                    Msg.number("players", playerCounts().getOrDefault(world.toLowerCase(Locale.ROOT), 0)));
        } else {
            String environment = known != null && known.environment() != null
                    ? known.environment().name().toLowerCase(Locale.ROOT) : "?";
            ctx.messages().send(sender, "worlds.info-environment", Msg.unparsed("environment", environment));
            ctx.messages().send(sender, "worlds.info-players", Msg.number("players", 0));
        }
        return Cmds.OK;
    }
}
