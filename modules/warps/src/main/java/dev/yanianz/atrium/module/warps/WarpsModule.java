package dev.yanianz.atrium.module.warps;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.warps.WarpAdminService;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.config.ConfigService;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Server warps with categories, per-warp permissions, a chest menu and player-owned warp limits. */
public final class WarpsModule implements AtriumModule, WarpService, WarpAdminService {
    static final String PERM_WARP = "atrium.warp";
    static final String PERM_ALL = "atrium.warps.all";
    static final String PERM_OTHERS = "atrium.warps.others";
    static final String PERM_SETWARP = "atrium.setwarp";
    static final String PERM_DELWARP = "atrium.delwarp";
    static final String PERM_EDITWARP = "atrium.editwarp";
    /** Allows players to create their own (limited) warps. */
    static final String PERM_CREATE = "atrium.warps.create";
    /** Numeric limit prefix: atrium.warps.limit.<n>, atrium.warps.limit.unlimited. */
    static final String LIMIT_PREFIX = "atrium.warps.limit";

    private ModuleContext ctx;
    private Cmds cmds;
    private Messages messages;
    private ConfigHandle<WarpsConfig> config;
    private WarpManager manager;
    private ExtendedTeleportService teleport;
    private WarpMenu menu;
    private final Object iconLock = new Object();

    @Override
    public String id() {
        return "warps";
    }

    @Override
    public String description() {
        return "Warps: /warp /warps /setwarp /delwarp /renamewarp /setwarpicon with a chest menu and per-warp permissions.";
    }

    @Override
    public Set<String> dependencies() {
        return Set.of("teleport");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.config = ctx.config(WarpsConfig.class, WarpsConfig::new);
        this.teleport = ctx.services().require(ExtendedTeleportService.class);
        ctx.migrate(SqlWarpStorage.MIGRATIONS);
        this.manager = new WarpManager(new SqlWarpStorage(ctx.database()), event -> event.callEvent(), event -> event.callEvent());
        manager.load().get(30, TimeUnit.SECONDS);
        this.menu = new WarpMenu(ctx, this, config, teleport);

        ctx.permission(PERM_WARP, PermissionDefault.TRUE, "Use /warp and /warps");
        ctx.permission(PERM_ALL, PermissionDefault.OP, "Use every warp when per-warp permissions are required");
        ctx.permission(PERM_OTHERS, PermissionDefault.OP, "Send other players to a warp");
        ctx.permission(PERM_SETWARP, PermissionDefault.OP, "Create, move and rename any warp");
        ctx.permission(PERM_DELWARP, PermissionDefault.OP, "Delete any warp");
        ctx.permission(PERM_EDITWARP, PermissionDefault.OP,
                "Open the /editwarp editor; each change also needs atrium.setwarp (atrium.delwarp to delete) or ownership");
        ctx.permission(PERM_CREATE, PermissionDefault.TRUE,
                "Create, move, rename and delete your own warps, up to your atrium.warps.limit");
        ctx.permission(LIMIT_PREFIX + ".unlimited", PermissionDefault.OP, "Own unlimited warps");

        ctx.command(CommandSpec.of(Cmds.literal("warp", PERM_WARP)
                .executes(c -> listText(c, null))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(usableWarps())
                        .executes(this::warp)
                        .then(Commands.argument("player", ArgumentTypes.player()).requires(Cmds.permission(PERM_OTHERS))
                                .executes(this::warp)))
                .build(), "Teleport to a warp, or send another player there"));
        ctx.command(CommandSpec.of(Cmds.literal("warps", PERM_WARP)
                .executes(c -> show(c, null))
                .then(Commands.argument("category", StringArgumentType.word()).suggests(categories())
                        .executes(c -> show(c, StringArgumentType.getString(c, "category"))))
                .build(), "Open the warp menu (or list warps in chat), optionally for one category"));
        ctx.command(CommandSpec.of(Cmds.literal("setwarp")
                .requires(c -> c.getSender().hasPermission(PERM_SETWARP) || c.getSender().hasPermission(PERM_CREATE))
                .executes(c -> usage(c, "warps.usage-setwarp"))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(manageableWarps())
                        .executes(this::setWarp)
                        .then(Commands.argument("category", StringArgumentType.word()).suggests(categories())
                                .executes(this::setWarp)))
                .build(), "Create a warp where you stand, or move one of yours", "createwarp"));
        ctx.command(CommandSpec.of(Cmds.literal("delwarp")
                .requires(c -> c.getSender().hasPermission(PERM_DELWARP) || c.getSender().hasPermission(PERM_CREATE))
                .executes(c -> usage(c, "warps.usage-delwarp"))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(manageableWarps()).executes(this::delWarp))
                .build(), "Delete a warp permanently", "removewarp", "rmwarp"));
        ctx.command(CommandSpec.of(Cmds.literal("renamewarp")
                .requires(c -> c.getSender().hasPermission(PERM_SETWARP) || c.getSender().hasPermission(PERM_CREATE))
                .executes(c -> usage(c, "warps.usage-renamewarp"))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(manageableWarps())
                        .executes(c -> usage(c, "warps.usage-renamewarp"))
                        .then(Commands.argument("new-name", StringArgumentType.word()).executes(this::renameWarp)))
                .build(), "Rename a warp (its icon follows)"));
        ctx.command(CommandSpec.of(Cmds.literal("setwarpicon", PERM_SETWARP)
                .executes(c -> usage(c, "warps.usage-setwarpicon"))
                .then(Commands.argument("name", StringArgumentType.word()).suggests(allWarps()).executes(this::setWarpIcon))
                .build(), "Use the item in your hand as the menu icon of a warp"));

        WarpEditor editor = new WarpEditor(this, ctx);
        ctx.command(CommandSpec.of(Cmds.literal("editwarp", PERM_EDITWARP)
                .executes(editor::usage)
                .then(Commands.argument("warp", StringArgumentType.word()).suggests(allWarps())
                        .executes(editor::show)
                        .then(Cmds.literal("move").executes(editor::move))
                        .then(Cmds.literal("rename").executes(editor::usage)
                                .then(Commands.argument("new-name", StringArgumentType.word())
                                        .executes(editor::rename)))
                        .then(Cmds.literal("category").executes(editor::usage)
                                .then(Commands.argument("category", StringArgumentType.word())
                                        .suggests(categories()).executes(editor::category)))
                        .then(Cmds.literal("permission").executes(editor::usage)
                                .then(Commands.argument("node", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("none")))
                                        .executes(editor::permission)))
                        .then(Cmds.literal("icon").executes(editor::icon))
                        .then(Cmds.literal("delete")
                                .executes(editor::deleteConfirm)
                                .then(Cmds.literal("confirm").executes(editor::delete))))
                .build(), "Show a warp's details with clickable edit actions", "warpeditor"));

        ctx.service(WarpService.class, this);
        ctx.service(WarpAdminService.class, this);
    }

    @Override
    public void disable() {
        // Open warp menus are closed by the menu engine when the module is disabled.
        menu = null;
    }

    // ---- WarpService ----

    @Override
    public Collection<String> names() {
        return manager.all().stream().map(Warp::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    @Override
    public Optional<StoredLocation> location(String name) {
        return manager.get(name).map(Warp::location);
    }

    @Override
    public Optional<String> category(String name) {
        return manager.get(name).map(Warp::category);
    }

    // ---- WarpAdminService ----

    @Override
    public CompletableFuture<Boolean> importWarp(String name, Location location,
            @Nullable String category, @Nullable String permission, @Nullable UUID creator, boolean overwrite) {
        if (location == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("location is required"));
        }
        StoredLocation stored;
        try {
            stored = StoredLocation.of(location);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid warp location", e));
        }
        String resolvedCategory = category == null || category.isBlank() ? config.get().defaultCategory : category.trim();
        if (!Warp.validName(name) || !Warp.validName(resolvedCategory)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "invalid warp name or category: " + name + " / " + resolvedCategory));
        }
        if (permission != null && !permission.isBlank() && !Warp.validPermission(permission)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid warp permission: " + permission));
        }
        // The create event must run on the global region thread, and the write is only reported once the
        // storage future (inside the manager) completed.
        return ctx.scheduler()
                .supplyGlobal(() -> manager.importWarp(ctx.plugin().getServer().getConsoleSender(), name,
                        resolvedCategory, stored, permission, creator, overwrite))
                .thenCompose(future -> future)
                .thenApply(outcome -> switch (outcome) {
                    case CREATED, MOVED -> Boolean.TRUE;
                    case EXISTS, CANCELLED -> Boolean.FALSE;
                    case INVALID_NAME -> throw new CompletionException(
                            new IllegalArgumentException("invalid warp name or category: " + name));
                    case INVALID_PERMISSION -> throw new CompletionException(
                            new IllegalArgumentException("invalid warp permission: " + permission));
                    default -> throw new CompletionException(
                            new IllegalStateException("Unexpected warp import outcome: " + outcome));
                });
    }

    // ---- access ----

    WarpManager manager() {
        return manager;
    }

    boolean canUse(CommandSender sender, Warp warp) {
        if (sender instanceof Player player && player.getUniqueId().equals(warp.creator())) {
            return true; // a player can always use the warp they own
        }
        return !config.get().requirePerWarpPermission || sender.hasPermission(PERM_ALL) || sender.hasPermission(warp.permission());
    }

    private SuggestionProvider<CommandSourceStack> usableWarps() {
        return (c, builder) -> {
            CommandSender sender = c.getSource().getSender();
            String typed = builder.getRemainingLowerCase();
            manager.all().stream().filter(w -> canUse(sender, w)).map(Warp::name)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).sorted().forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> allWarps() {
        return Cmds.suggest(this::names);
    }

    /** Staff see every warp; a player with atrium.warps.create only sees the warps they own. */
    private SuggestionProvider<CommandSourceStack> manageableWarps() {
        return (c, builder) -> {
            CommandSender sender = c.getSource().getSender();
            boolean staff = sender.hasPermission(PERM_SETWARP) || sender.hasPermission(PERM_DELWARP);
            String typed = builder.getRemainingLowerCase();
            manager.all().stream()
                    .filter(w -> staff || (sender instanceof Player player
                            && player.hasPermission(PERM_CREATE) && player.getUniqueId().equals(w.creator())))
                    .map(Warp::name)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .forEach(builder::suggest);
            return builder.buildFuture();
        };
    }

    private SuggestionProvider<CommandSourceStack> categories() {
        return Cmds.suggest(() -> manager.all().stream().map(Warp::category).distinct().sorted().toList());
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        reply(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    // ---- /warp ----

    private int warp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        Player target = Cmds.hasArgument(c, "player") ? cmds.argPlayer(c, "player") : cmds.player(c.getSource());
        boolean self = target.equals(sender);
        if (!self && !sender.hasPermission(PERM_OTHERS)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", PERM_OTHERS));
        }
        Warp warp = manager.get(name).filter(w -> canUse(sender, w))
                // Unknown and forbidden warps get the same answer, so hidden warps stay hidden.
                .orElseThrow(() -> cmds.fail(c.getSource(), "warps.not-found", Msg.unparsed("warp", name)));
        Optional<Location> location = warp.location().toLocation();
        // A warp in a world that is not loaded may be in an AWF world that loads on demand (WorldResolver).
        var storedTeleports = ctx.services().get(dev.yanianz.atrium.api.teleport.StoredTeleportService.class);
        if (location.isEmpty() && storedTeleports.isEmpty()) {
            throw cmds.fail(c.getSource(), "warps.world-not-loaded", Msg.unparsed("world", warp.location().world()));
        }
        TeleportOptions options = self || config.get().warmupWhenSendingOthers ? TeleportOptions.DEFAULT : TeleportOptions.IMMEDIATE;
        java.util.concurrent.CompletableFuture<TeleportResult> move = location.isPresent()
                ? teleport.teleport(target, location.get(), TeleportCause.WARP, options)
                : storedTeleports.get().teleport(target, warp.location(), TeleportCause.WARP, options)
                        .thenApply(dev.yanianz.atrium.api.teleport.StoredTeleportService.StoredTeleportResult::result);
        move.thenAccept(result -> {
            if (result != TeleportResult.SUCCESS) {
                return;
            }
            messages.send(target, "warps.teleported", Msg.unparsed("warp", warp.name()));
            if (!self) {
                reply(sender, "warps.teleported-other", Msg.unparsed("warp", warp.name()),
                        Msg.unparsed("player", target.getName()));
            }
        });
        return Cmds.OK;
    }

    // ---- /warps ----

    /** Players with the menu enabled get a chest menu; console and text-mode servers get the list. */
    private int show(CommandContext<CommandSourceStack> c, @Nullable String onlyCategory) {
        CommandSender sender = c.getSource().getSender();
        if (sender instanceof Player player && config.get().guiEnabled) {
            menu.open(player, onlyCategory);
            return Cmds.OK;
        }
        return listText(c, onlyCategory);
    }

    private int listText(CommandContext<CommandSourceStack> c, @Nullable String onlyCategory) {
        CommandSender sender = c.getSource().getSender();
        Map<String, List<Warp>> byCategory = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Warp warp : manager.all()) {
            if (!canUse(sender, warp)) {
                continue;
            }
            if (onlyCategory != null && !warp.category().equalsIgnoreCase(onlyCategory)) {
                continue;
            }
            byCategory.computeIfAbsent(warp.category(), k -> new ArrayList<>()).add(warp);
        }
        int total = byCategory.values().stream().mapToInt(List::size).sum();
        messages.send(sender, "warps.list-header", Msg.number("count", total));
        if (total == 0) {
            messages.send(sender, "warps.list-empty");
            return Cmds.OK;
        }
        Component separator = messages.render(sender, "warps.list-separator");
        byCategory.forEach((category, warps) -> {
            warps.sort(Comparator.comparing(Warp::name, String.CASE_INSENSITIVE_ORDER));
            List<Component> entries = new ArrayList<>();
            for (Warp warp : warps) {
                StoredLocation l = warp.location();
                Component hover = messages.render(sender, "warps.list-hover", Msg.unparsed("warp", warp.name()),
                        Msg.unparsed("world", l.world()), Msg.number("x", l.blockX()), Msg.number("y", l.blockY()),
                        Msg.number("z", l.blockZ()));
                // Warp names are validated ([A-Za-z0-9_-]), so they are safe inside a command.
                entries.add(messages.render(sender, "warps.list-entry", Msg.unparsed("warp", warp.name()))
                        .clickEvent(ClickEvent.runCommand("/warp " + warp.name()))
                        .hoverEvent(HoverEvent.showText(hover)));
            }
            sender.sendMessage(messages.render(sender, "warps.list-category", Msg.unparsed("category", category),
                    Msg.number("count", warps.size()),
                    Msg.component("warps", Component.join(JoinConfiguration.separator(separator), entries))));
        });
        return Cmds.OK;
    }

    // ---- admin and player-owned warps ----

    private int setWarp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name");
        Optional<Warp> existing = manager.get(name);
        if (existing.isPresent() && !mayManage(self, existing.get(), PERM_SETWARP)) {
            throw cmds.fail(c.getSource(), "warps.not-owner", Msg.unparsed("warp", existing.get().name()));
        }
        String category = Cmds.hasArgument(c, "category")
                ? StringArgumentType.getString(c, "category")
                : existing.map(Warp::category).orElse(config.get().defaultCategory);
        if (!Warp.validName(name) || !Warp.validName(category)) {
            throw cmds.fail(c.getSource(), "warps.invalid-name");
        }
        // Staff may move or create any warp; a player with atrium.warps.create owns at most their limit.
        boolean staff = self.hasPermission(PERM_SETWARP);
        int limit = staff ? Integer.MAX_VALUE
                : Permissions.limit(self, LIMIT_PREFIX, Math.max(0, config.get().defaultPlayerWarpLimit));
        Location here = self.getLocation();
        report(self, manager.set(self, name, category, StoredLocation.of(here), self.getUniqueId(), limit),
                name, null, Msg.unparsed("category", category), Msg.location("at", here),
                Msg.number("limit", limit == Integer.MAX_VALUE ? -1 : limit));
        return Cmds.OK;
    }

    private int delWarp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        Optional<Warp> existing = manager.get(name);
        if (existing.isPresent() && !mayManage(sender, existing.get(), PERM_DELWARP)) {
            throw cmds.fail(c.getSource(), "warps.not-owner", Msg.unparsed("warp", existing.get().name()));
        }
        report(sender, manager.delete(sender, name), name, null);
        return Cmds.OK;
    }

    private int renameWarp(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "name");
        String newName = StringArgumentType.getString(c, "new-name");
        Optional<Warp> existing = manager.get(name);
        if (existing.isPresent() && !mayManage(sender, existing.get(), PERM_SETWARP)) {
            throw cmds.fail(c.getSource(), "warps.not-owner", Msg.unparsed("warp", existing.get().name()));
        }
        report(sender, manager.rename(sender, name, newName), name, newName);
        return Cmds.OK;
    }

    /** /setwarpicon uses only the material and custom model data, never the full item. */
    private int setWarpIcon(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        String name = StringArgumentType.getString(c, "name");
        Warp warp = manager.get(name)
                .orElseThrow(() -> cmds.fail(c.getSource(), "warps.not-found", Msg.unparsed("warp", name)));
        return applyIcon(self, warp);
    }

    /**
     * Stores the item in {@code self}'s main hand as the menu icon. Shared by {@code /setwarpicon} and the
     * {@code /editwarp} editor, so the two paths cannot disagree about which item data is kept.
     */
    @SuppressWarnings("deprecation") // integer custom model data is the format stored in warps.yml
    int applyIcon(Player self, Warp warp) {
        ItemStack hand = self.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            messages.send(self, "warps.icon-empty-hand");
            return Cmds.OK;
        }
        ItemMeta meta = hand.getItemMeta();
        int modelData = meta != null && meta.hasCustomModelData() ? meta.getCustomModelData() : -1;
        WarpsConfig.Icon icon = new WarpsConfig.Icon(hand.getType().name(), modelData);
        updateIcons(icons -> icons.put(warp.key(), icon)).whenComplete((done, error) ->
                ctx.scheduler().entity(self, () -> {
                    if (error != null) {
                        ctx.logger().error("/setwarpicon: saving the icon of {} failed", warp.name(), error);
                        messages.send(self, "warps.save-failed");
                        return;
                    }
                    messages.send(self, "warps.icon-set", Msg.unparsed("warp", warp.name()));
                }, null));
        return Cmds.OK;
    }

    WarpsConfig config() {
        return config.get();
    }

    /** Staff, or the creator of the warp (when they have atrium.warps.create), may change it. */
    static boolean mayManage(CommandSender sender, Warp warp, String adminPermission) {
        boolean owner = sender instanceof Player player && player.getUniqueId().equals(warp.creator());
        return WarpEditPolicy.mayManage(sender.hasPermission(adminPermission), sender.hasPermission(PERM_CREATE), owner);
    }

    void report(CommandSender sender, CompletableFuture<WarpManager.Outcome> future, String name,
            @Nullable String newName,
            TagResolver... extra) {
        future.whenComplete((outcome, error) -> {
            if (error != null) {
                ctx.logger().error("Saving warp {} failed", name, error);
                reply(sender, "warps.save-failed");
                return;
            }
            String key = switch (outcome) {
                case CREATED -> "warps.created";
                case MOVED -> "warps.moved";
                case DELETED -> "warps.deleted";
                case RENAMED -> "warps.renamed";
                case CANCELLED -> "warps.cancelled";
                case NOT_FOUND -> "warps.not-found";
                case EXISTS -> "warps.exists";
                case INVALID_NAME -> "warps.invalid-name";
                case INVALID_PERMISSION -> "warps.invalid-permission";
                case LIMIT_REACHED -> "warps.limit-reached";
            };
            if (outcome == WarpManager.Outcome.RENAMED && newName != null) {
                updateIcons(icons -> {
                    WarpsConfig.Icon icon = icons.remove(Warp.key(name));
                    if (icon != null) {
                        icons.put(Warp.key(newName), icon);
                    }
                }).exceptionally(iconError -> {
                    ctx.logger().warn("Moving the icon of warp {} to {} failed", name, newName, iconError);
                    return false;
                });
            } else if (outcome == WarpManager.Outcome.DELETED) {
                updateIcons(icons -> icons.remove(Warp.key(name))).exceptionally(iconError -> {
                    ctx.logger().warn("Removing the icon of deleted warp {} failed", name, iconError);
                    return false;
                });
            }
            var resolvers = new ArrayList<TagResolver>(List.of(extra));
            resolvers.add(Msg.unparsed("warp", name));
            resolvers.add(Msg.unparsed("new_name", newName == null ? "" : newName));
            reply(sender, key, resolvers.toArray(TagResolver[]::new));
        });
    }

    /** Sends on the recipient's own thread; warp writes finish on a database thread. */
    void reply(CommandSender recipient, String key, TagResolver... resolvers) {
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> messages.send(player, key, resolvers), null);
        } else {
            messages.send(recipient, key, resolvers);
        }
    }

    /**
     * Applies {@code change} to the icons of warps.yml, writes the file on the I/O executor and reloads
     * the config. Nothing changes in memory when the write fails. All icon writes share one lock, so two
     * commands cannot lose each other's change.
     */
    CompletableFuture<Boolean> updateIcons(Consumer<Map<String, WarpsConfig.Icon>> change) {
        return ctx.scheduler().supplyIo(() -> {
            synchronized (iconLock) {
                WarpsConfig current = config.get();
                Map<String, WarpsConfig.Icon> previous = current.icons;
                Map<String, WarpsConfig.Icon> next = new LinkedHashMap<>(previous == null ? Map.of() : previous);
                change.accept(next);
                if (next.equals(previous)) {
                    return Boolean.TRUE;
                }
                current.icons = next;
                try {
                    YamlConfigurationLoader loader = ConfigService.loader(config.path());
                    CommentedConfigurationNode node = loader.load();
                    node.set(WarpsConfig.class, current);
                    loader.save(node);
                    config.reload();
                    return Boolean.TRUE;
                } catch (Exception e) {
                    current.icons = previous;
                    throw new java.util.concurrent.CompletionException("Saving the warp icons failed", e);
                }
            }
        });
    }
}
