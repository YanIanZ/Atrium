package dev.yanianz.atrium.module.warps;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.economy.EconomyService;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.warps.WarpAdminService;
import dev.yanianz.atrium.api.warps.WarpImportService;
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
import java.math.BigDecimal;
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
import java.time.Duration;
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
public final class WarpsModule implements AtriumModule, WarpService, WarpAdminService, WarpImportService {
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
    /** Bypasses per-warp costs and cooldowns. */
    static final String PERM_FREE = "atrium.warps.free";
    static final String PERM_WARPGROUPS = "atrium.warpgroups";

    private ModuleContext ctx;
    private Cmds cmds;
    private Messages messages;
    private ConfigHandle<WarpsConfig> config;
    private WarpManager manager;
    private ExtendedTeleportService teleport;
    private WarpMenu menu;
    private SqlWarpLocations locationStore;
    private WarpDecks decks;
    /** Extra random locations per warp key, loaded once at enable and updated after every change. */
    private volatile Map<String, List<StoredLocation>> extraLocations = Map.of();
    private final Object iconLock = new Object();

    @Override
    public String id() {
        return "warps";
    }

    @Override
    public String description() {
        return "Warps: /warp /warps /warpgroups /setwarp /delwarp /renamewarp /setwarpicon with a chest menu, "
                + "per-warp permissions, costs, cooldowns and random-location decks.";
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
        this.locationStore = new SqlWarpLocations(ctx.database());
        this.extraLocations = locationStore.loadAll().get(30, TimeUnit.SECONDS);
        this.decks = new WarpDecks(config::get);
        ctx.listen(decks);
        this.menu = new WarpMenu(ctx, this, config);
        String randomProblem = config.get().randomModeProblem();
        if (randomProblem != null) {
            ctx.logger().warn("modules/warps.yml {}", randomProblem);
        }

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
        ctx.permission(PERM_FREE, PermissionDefault.FALSE, "Bypass warp costs and cooldowns");
        ctx.permission(PERM_WARPGROUPS, PermissionDefault.TRUE, "List the warp groups and their warps");

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
        ctx.command(CommandSpec.of(Cmds.literal("warpgroups", PERM_WARPGROUPS)
                .executes(this::warpGroups)
                .then(Commands.argument("group", StringArgumentType.word()).suggests(categories())
                        .executes(c -> listText(c, StringArgumentType.getString(c, "group"))))
                .build(), "List the warp groups (categories) with their warp counts", "warpgroup"));
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
                        .then(Cmds.literal("cost").executes(editor::costUsage)
                                .then(Commands.argument("amount", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("0", "10", "100", "500")))
                                        .executes(editor::cost)))
                        .then(Cmds.literal("cooldown").executes(editor::cooldownUsage)
                                .then(Commands.argument("seconds", StringArgumentType.word())
                                        .suggests(Cmds.suggest(() -> List.of("0", "10", "30", "60", "300")))
                                        .executes(editor::cooldown)))
                        .then(Cmds.literal("addlocation").executes(editor::addLocation))
                        .then(Cmds.literal("locations").executes(editor::locations))
                        .then(Cmds.literal("clearlocations").executes(editor::clearLocationsConfirm)
                                .then(Cmds.literal("confirm").executes(editor::clearLocations)))
                        .then(Cmds.literal("delete")
                                .executes(editor::deleteConfirm)
                                .then(Cmds.literal("confirm").executes(editor::delete))))
                .build(), "Show a warp's details with clickable edit actions", "warpeditor"));

        ctx.service(WarpService.class, this);
        ctx.service(WarpAdminService.class, this);
        ctx.service(WarpImportService.class, this);
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

    // ---- WarpImportService ----

    @Override
    public CompletableFuture<Boolean> importWarp(WarpImport input, boolean overwrite) {
        String category = input.category() == null || input.category().isBlank()
                ? config.get().defaultCategory : input.category().trim();
        if (!Warp.validName(input.name()) || !Warp.validName(category)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "invalid warp name or category: " + input.name() + " / " + category));
        }
        String permission = input.permission() == null ? "" : input.permission().strip();
        if (!permission.isEmpty() && !Warp.validPermission(permission)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("invalid warp permission: " + permission));
        }
        org.bukkit.Material icon = null;
        if (input.iconMaterial() != null && !input.iconMaterial().isBlank()) {
            icon = org.bukkit.Material.matchMaterial(input.iconMaterial().trim());
            if (icon == null || !icon.isItem() || icon.isAir()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException(
                        "invalid warp icon material: " + input.iconMaterial()));
            }
        }
        long created = input.createdAt() > 0 ? input.createdAt() : System.currentTimeMillis();
        Warp warp = new Warp(input.name(), category, input.location(), input.owner(), created, permission);
        String iconName = icon == null ? null : icon.name();
        int modelData = Math.max(-1, input.iconModelData());
        return ctx.scheduler()
                .supplyGlobal(() -> manager.importFull(ctx.plugin().getServer().getConsoleSender(), warp, overwrite))
                .thenCompose(future -> future)
                .thenCompose(outcome -> switch (outcome) {
                    case CREATED, MOVED -> iconName == null ? CompletableFuture.completedFuture(Boolean.TRUE)
                            : updateIcons(icons -> icons.put(warp.key(), new WarpsConfig.Icon(iconName, modelData)))
                                    .thenApply(ignored -> Boolean.TRUE);
                    case EXISTS, CANCELLED -> CompletableFuture.completedFuture(Boolean.FALSE);
                    case INVALID_NAME -> CompletableFuture.failedFuture(
                            new IllegalArgumentException("invalid warp name or category: " + input.name()));
                    case INVALID_PERMISSION -> CompletableFuture.failedFuture(
                            new IllegalArgumentException("invalid warp permission: " + permission));
                    default -> CompletableFuture.failedFuture(
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
        useWarp(target, warp, self ? null : sender);
        return Cmds.OK;
    }

    /**
     * Teleports {@code target} to {@code warp} and applies its cost and cooldown. Runs on the player's thread.
     *
     * <p>The order is fixed: cooldown first (a waiting player loses no money), then the charge, then the
     * teleport. A teleport that does not succeed refunds the charge and releases the cooldown, so a failed
     * move never costs anything. {@code actor} is {@code null} when the player warps themselves and the
     * sending staff member otherwise.
     */
    void useWarp(Player target, Warp warp, @Nullable CommandSender actor) {
        boolean self = actor == null || actor.equals(target);
        WarpsConfig cfg = config.get();
        int cooldownSeconds = (int) Math.min(Integer.MAX_VALUE, cfg.cooldownSeconds(warp.key()));
        boolean bypass = target.hasPermission(PERM_FREE);
        if (cooldownSeconds > 0 && !bypass) {
            Duration left = ctx.cooldowns().tryAcquire(ctx.users().user(target), "warps:" + warp.key(),
                    Duration.ofSeconds(cooldownSeconds));
            if (!left.isZero()) {
                messages.send(target, "warps.cooldown", Msg.unparsed("warp", warp.name()),
                        Msg.unparsed("time", duration(left)));
                return;
            }
        }
        double configuredCost = cfg.cost(warp.key());
        boolean charge = configuredCost > 0 && !bypass && (self || cfg.chargeWhenSendingOthers);
        if (!charge) {
            startTeleport(target, warp, actor, self, 0);
            return;
        }
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        if (economy == null) {
            ctx.logger().warn("Warp {} has a cost but the economy module is not enabled; the warp is refused",
                    warp.name());
            messages.send(target, "warps.economy-missing");
            releaseCooldown(target, warp);
            return;
        }
        BigDecimal amount = BigDecimal.valueOf(configuredCost);
        economy.withdraw(target.getUniqueId(), amount, "warp " + warp.name())
                .whenComplete((result, error) -> {
                    boolean paid = error == null && result != null && result.success();
                    if (paid && !onPlayerThread(target, () ->
                            startTeleport(target, warp, actor, self, configuredCost))) {
                        // The player left while the charge was in flight: give the money back, the move is lost.
                        refund(target, warp, configuredCost);
                        return;
                    }
                    if (paid) {
                        return;
                    }
                    if (!onPlayerThread(target, () -> {
                        if (error != null) {
                            ctx.logger().error("Charging {} for warp {} failed", target.getName(), warp.name(), error);
                            messages.send(target, "warps.cost-failed");
                        } else {
                            messages.send(target, "warps.cost-insufficient",
                                    Msg.unparsed("warp", warp.name()),
                                    Msg.unparsed("cost", economy.format(amount)),
                                    Msg.unparsed("balance", economy.format(result.balance())));
                        }
                        releaseCooldown(target, warp);
                    })) {
                        // Nothing to tell an offline player; no money was taken.
                    }
                });
    }

    /** Performs the move once it is paid for (or free) and refunds on failure. Player thread. */
    private void startTeleport(Player target, Warp warp, @Nullable CommandSender actor, boolean self, double paid) {
        StoredLocation chosen = pickLocation(target, warp);
        Optional<Location> location = chosen.toLocation();
        var storedTeleports = ctx.services().get(dev.yanianz.atrium.api.teleport.StoredTeleportService.class);
        if (location.isEmpty() && storedTeleports.isEmpty()) {
            refund(target, warp, paid);
            messages.send(target, "warps.world-not-loaded", Msg.unparsed("world", chosen.world()));
            return;
        }
        TeleportOptions options = self || config.get().warmupWhenSendingOthers
                ? TeleportOptions.DEFAULT : TeleportOptions.IMMEDIATE;
        CompletableFuture<TeleportResult> move = location.isPresent()
                ? teleport.teleport(target, location.get(), TeleportCause.WARP, options)
                : storedTeleports.get().teleport(target, chosen, TeleportCause.WARP, options)
                        .thenApply(dev.yanianz.atrium.api.teleport.StoredTeleportService.StoredTeleportResult::result);
        move.whenComplete((result, error) -> {
            if (error != null || result != TeleportResult.SUCCESS) {
                if (error != null) {
                    ctx.logger().warn("Teleporting {} to warp {} failed", target.getName(), warp.name(), error);
                }
                // The refund is a balance write and works even when the player already left.
                refund(target, warp, paid);
                onPlayerThread(target, () -> releaseCooldown(target, warp));
                return;
            }
            onPlayerThread(target, () -> {
                messages.send(target, "warps.teleported", Msg.unparsed("warp", warp.name()));
                if (!self && actor != null) {
                    reply(actor, "warps.teleported-other", Msg.unparsed("warp", warp.name()),
                            Msg.unparsed("player", target.getName()));
                }
            });
        });
    }

    /** The warp's own location plus its extra locations, in a stable order. */
    private List<StoredLocation> pool(Warp warp) {
        List<StoredLocation> extra = extraLocations.getOrDefault(warp.key(), List.of());
        if (extra.isEmpty()) {
            return List.of(warp.location());
        }
        List<StoredLocation> pool = new ArrayList<>(extra.size() + 1);
        pool.add(warp.location());
        pool.addAll(extra);
        return List.copyOf(pool);
    }

    private StoredLocation pickLocation(Player player, Warp warp) {
        List<StoredLocation> pool = pool(warp);
        return pool.get(decks.choose(player, warp.key(), pool.size()));
    }

    private void refund(Player target, Warp warp, double paid) {
        if (paid <= 0) {
            return;
        }
        EconomyService economy = ctx.services().get(EconomyService.class).orElse(null);
        if (economy == null) {
            return;
        }
        BigDecimal amount = BigDecimal.valueOf(paid);
        economy.deposit(target.getUniqueId(), amount, "warp refund " + warp.name())
                .whenComplete((result, error) -> {
                    if (error != null || result == null || !result.success()) {
                        ctx.logger().error("Refunding {} of warp {} to {} failed; refund manually", amount,
                                warp.name(), target.getName(), error);
                    }
                });
    }

    private void releaseCooldown(Player target, Warp warp) {
        if (!target.isOnline()) {
            return;
        }
        ctx.cooldowns().clear(ctx.users().user(target), "warps:" + warp.key());
    }

    /**
     * Runs {@code task} on the player's own thread. Returns {@code false} when the player already left (or the
     * schedule failed), so the caller can refund instead of losing the money.
     */
    private boolean onPlayerThread(Player player, Runnable task) {
        if (!player.isOnline()) {
            return false;
        }
        try {
            ctx.scheduler().entity(player, task, null);
            return true;
        } catch (RuntimeException error) {
            ctx.logger().debug("Could not schedule a warp result for {}", player.getName(), error);
            return false;
        }
    }

    /** A short "3m 12s" style duration for messages. */
    static String duration(Duration duration) {
        long seconds = Math.max(0, duration.toSeconds());
        long minutes = seconds / 60;
        long rest = seconds % 60;
        return minutes > 0 ? minutes + "m " + rest + "s" : rest + "s";
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

    /** Lists every warp group the sender may use, with a clickable count; empty groups are left out. */
    private int warpGroups(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Map<String, Integer> counts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Warp warp : manager.all()) {
            if (canUse(sender, warp)) {
                counts.merge(warp.category(), 1, Integer::sum);
            }
        }
        messages.send(sender, "warps.groups-header", Msg.number("groups", counts.size()),
                Msg.number("warps", counts.values().stream().mapToInt(Integer::intValue).sum()));
        if (counts.isEmpty()) {
            messages.send(sender, "warps.groups-empty");
            return Cmds.OK;
        }
        Component separator = messages.render(sender, "warps.list-separator");
        List<Component> entries = new ArrayList<>();
        counts.forEach((group, count) -> entries.add(messages.render(sender, "warps.groups-entry",
                        Msg.unparsed("group", group), Msg.number("count", count))
                // Group names are validated ([A-Za-z0-9_-]), so they are safe inside a command.
                .clickEvent(ClickEvent.runCommand("/warps " + group))
                .hoverEvent(HoverEvent.showText(messages.render(sender, "warps.groups-hover",
                        Msg.unparsed("group", group), Msg.number("count", count))))));
        sender.sendMessage(messages.render(sender, "warps.groups-list",
                Msg.component("groups", Component.join(JoinConfiguration.separator(separator), entries))));
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
                moveExtraLocations(name, newName);
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
                forgetExtraLocations(name);
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

    // ---- random locations and per-warp settings ----

    /** Extra random locations of a warp, in the order they were added. */
    List<StoredLocation> extraLocations(String warpKey) {
        return extraLocations.getOrDefault(warpKey, List.of());
    }

    /** Size of the location deck: the warp's own location plus every extra one. */
    int poolSize(Warp warp) {
        return 1 + extraLocations(warp.key()).size();
    }

    /** Appends a location; completes with the new pool size, or -1 when the warp is already full. */
    CompletableFuture<Integer> addExtraLocation(Warp warp, StoredLocation location) {
        return locationStore.add(warp.key(), location).thenApply(count -> {
            if (count <= 0) {
                return -1;
            }
            Map<String, List<StoredLocation>> next = new LinkedHashMap<>(extraLocations);
            List<StoredLocation> list = new ArrayList<>(next.getOrDefault(warp.key(), List.of()));
            list.add(location);
            next.put(warp.key(), List.copyOf(list));
            extraLocations = Map.copyOf(next);
            return list.size();
        });
    }

    /** Removes every extra location; completes with the number of removed rows. */
    CompletableFuture<Integer> clearExtraLocations(Warp warp) {
        return locationStore.clear(warp.key()).thenApply(removed -> {
            Map<String, List<StoredLocation>> next = new LinkedHashMap<>(extraLocations);
            next.remove(warp.key());
            extraLocations = Map.copyOf(next);
            return removed;
        });
    }

    private void moveExtraLocations(String from, String to) {
        String oldKey = Warp.key(from);
        String newKey = Warp.key(to);
        locationStore.rename(oldKey, newKey).whenComplete((moved, error) -> {
            if (error != null) {
                ctx.logger().warn("Moving the extra locations of warp {} to {} failed", from, to, error);
                return;
            }
            Map<String, List<StoredLocation>> next = new LinkedHashMap<>(extraLocations);
            List<StoredLocation> list = next.remove(oldKey);
            if (list != null) {
                next.put(newKey, list);
            }
            extraLocations = Map.copyOf(next);
        });
    }

    private void forgetExtraLocations(String name) {
        String key = Warp.key(name);
        locationStore.clear(key).whenComplete((removed, error) -> {
            if (error != null) {
                ctx.logger().warn("Removing the extra locations of warp {} failed", name, error);
                return;
            }
            Map<String, List<StoredLocation>> next = new LinkedHashMap<>(extraLocations);
            next.remove(key);
            extraLocations = Map.copyOf(next);
        });
    }

    CompletableFuture<Boolean> saveCost(Warp warp, double cost) {
        return updateConfig(current -> {
            Map<String, Double> costs = current.costs == null ? new LinkedHashMap<>() : new LinkedHashMap<>(current.costs);
            // An explicit 0 is kept: it makes this warp free even when a default cost is configured.
            costs.put(warp.key(), Math.max(0, cost));
            current.costs = costs;
        });
    }

    CompletableFuture<Boolean> saveCooldown(Warp warp, long seconds) {
        return updateConfig(current -> {
            Map<String, Long> cooldowns = current.cooldowns == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(current.cooldowns);
            cooldowns.put(warp.key(), Math.max(0, seconds));
            current.cooldowns = cooldowns;
        });
    }

    /**
     * Applies {@code change} to a fresh copy of warps.yml, writes the file on the I/O executor and reloads the
     * config. Nothing changes in memory when the write fails. All config writes share one lock, so two
     * commands cannot lose each other's change.
     */
    CompletableFuture<Boolean> updateConfig(Consumer<WarpsConfig> change) {
        return ctx.scheduler().supplyIo(() -> {
            synchronized (iconLock) {
                YamlConfigurationLoader loader = ConfigService.loader(config.path());
                try {
                    CommentedConfigurationNode node = loader.load();
                    WarpsConfig next = node.empty() ? new WarpsConfig() : node.get(WarpsConfig.class);
                    if (next == null) {
                        next = new WarpsConfig();
                    }
                    change.accept(next);
                    node.set(WarpsConfig.class, next);
                    loader.save(node);
                    config.reload();
                    return Boolean.TRUE;
                } catch (Exception e) {
                    throw new java.util.concurrent.CompletionException("Saving warps.yml failed", e);
                }
            }
        });
    }

    /** Applies {@code change} to the icon map (see {@link #updateConfig}). */
    CompletableFuture<Boolean> updateIcons(Consumer<Map<String, WarpsConfig.Icon>> change) {
        return updateConfig(current -> {
            Map<String, WarpsConfig.Icon> icons = current.icons == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(current.icons);
            change.accept(icons);
            current.icons = icons;
        });
    }
}
