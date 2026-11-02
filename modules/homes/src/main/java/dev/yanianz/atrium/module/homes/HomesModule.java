package dev.yanianz.atrium.module.homes;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.yanianz.atrium.api.homes.HomeService;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;

/** Player homes with permission limits, a bed home and offline admin access. */
public final class HomesModule implements AtriumModule, HomeService {
    static final String PERM_HOME = "atrium.home";
    static final String PERM_SETHOME = "atrium.sethome";
    static final String PERM_DELHOME = "atrium.delhome";
    static final String PERM_OTHERS = "atrium.home.others";
    static final String PERM_BED = "atrium.home.bed";
    static final String PERM_BYPASS_WORLDS = "atrium.home.bypass.worlds";
    /** Numeric limit prefix: atrium.homes.<n>, atrium.homes.unlimited. */
    static final String LIMIT_PREFIX = "atrium.homes";

    private ModuleContext ctx;
    private Cmds cmds;
    private Messages messages;
    private ConfigHandle<HomesConfig> config;
    private HomeStore store;
    private ExtendedTeleportService teleport;

    @Override
    public String id() {
        return "homes";
    }

    @Override
    public String description() {
        return "Homes: /home /sethome /delhome /homes";
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
        this.config = ctx.config(HomesConfig.class, HomesConfig::new);
        this.teleport = ctx.services().require(ExtendedTeleportService.class);
        ctx.migrate(HomeStore.MIGRATIONS);
        this.store = new HomeStore(ctx.database());
        ctx.component(store.component);
        // SourbyCraft MultiProfile: homes may follow the active profile (config of the sourbycraft module).
        ctx.codec(store.component, HomesCodec.INSTANCE);
        ctx.profileScoped(new dev.yanianz.atrium.core.profile.ComponentScopedData("homes", store.component.id(),
                "Homes (/sethome, /home)", ctx.users()));

        ctx.permission(PERM_HOME, PermissionDefault.TRUE, "Teleport to and list own homes");
        ctx.permission(PERM_SETHOME, PermissionDefault.TRUE, "Set homes");
        ctx.permission(PERM_DELHOME, PermissionDefault.TRUE, "Delete own homes");
        ctx.permission(PERM_BED, PermissionDefault.TRUE, "Use the bed as a home");
        ctx.permission(PERM_OTHERS, PermissionDefault.OP, "Use, list and delete homes of other (also offline) players");
        ctx.permission(PERM_BYPASS_WORLDS, PermissionDefault.OP, "Set and use homes in restricted worlds");
        ctx.permission(LIMIT_PREFIX + ".unlimited", PermissionDefault.OP, "Unlimited homes");

        ctx.command(CommandSpec.of(Cmds.literal("home", PERM_HOME)
                .executes(this::home)
                .then(Commands.argument("spec", StringArgumentType.greedyString()).suggests(homeSuggestions())
                        .executes(this::home))
                .build(), "Teleport to a home: /home [player:]name [confirm]"));
        ctx.command(CommandSpec.of(Cmds.literal("sethome", PERM_SETHOME)
                .executes(this::setHome)
                .then(Commands.argument("name", StringArgumentType.word()).executes(this::setHome))
                .build(), "Save your location as a home", "createhome"));
        ctx.command(CommandSpec.of(Cmds.literal("delhome", PERM_DELHOME)
                .executes(this::usageDelhome)
                .then(Commands.argument("spec", StringArgumentType.greedyString()).suggests(homeSuggestions())
                        .executes(this::delHome))
                .build(), "Delete a home: /delhome [player:]name", "removehome", "rmhome"));
        ctx.command(CommandSpec.of(Cmds.literal("homes", PERM_HOME)
                .executes(this::listHomes)
                .then(Commands.argument("player", StringArgumentType.word())
                        .requires(Cmds.permission(PERM_OTHERS))
                        .suggests((c, b) -> {
                            ctx.users().knownNames(b.getRemaining(), 30).forEach(b::suggest);
                            return b.buildFuture();
                        })
                        .executes(this::listHomes))
                .build(), "List homes"));

        ctx.service(HomeService.class, this);
    }

    // ---- HomeService (administrative import/maintenance access) ----

    @Override
    public CompletableFuture<List<HomeInfo>> homes(UUID player) {
        Optional<AtriumUser> cached = ctx.users().cached(player);
        if (cached.isPresent()) {
            try {
                return CompletableFuture.completedFuture(toInfo(cached.get().get(store.component)));
            } catch (IllegalStateException e) {
                // Component still loading; fall through to the storage loader.
            }
        }
        // Read straight from storage when the player is not loaded: an import dry-run must not pull every
        // player into the offline user cache just to count homes.
        return store.component.loader().load(player)
                .thenApply(homes -> homes == null ? List.of() : toInfo(homes));
    }

    @Override
    public CompletableFuture<Result> replaceHomes(UUID player, List<HomeInfo> homes) {
        if (player == null || homes == null) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        List<Home> values = new ArrayList<>(homes.size());
        long now = System.currentTimeMillis();
        for (HomeInfo info : homes) {
            if (info == null || !Homes.validName(info.name())) {
                return CompletableFuture.completedFuture(Result.INVALID);
            }
            values.add(new Home(info.name(), info.location(), info.createdAt() > 0 ? info.createdAt() : now));
        }
        Homes value = Homes.of(values);
        return writeHomes(player, value).thenApply(ignored -> Result.SUCCESS);
    }

    @Override
    public CompletableFuture<Result> removeHome(UUID player, String name) {
        if (name == null || !Homes.validName(name)) {
            return CompletableFuture.completedFuture(Result.INVALID);
        }
        return homes(player).thenCompose(current -> {
            boolean existing = current.stream().anyMatch(home -> home.name().equalsIgnoreCase(name));
            if (!existing) {
                return CompletableFuture.completedFuture(Result.SKIPPED);
            }
            List<HomeInfo> remaining = current.stream()
                    .filter(home -> !home.name().equalsIgnoreCase(name))
                    .toList();
            return replaceHomes(player, remaining);
        });
    }

    private static List<HomeInfo> toInfo(Homes homes) {
        return homes.list().stream()
                .map(home -> new HomeInfo(home.name(), home.location(), home.createdAt()))
                .toList();
    }

    /**
     * Writes a whole snapshot. A loaded user is updated in memory and saved through the user service so a
     * later flush cannot overwrite the imported rows with a stale snapshot.
     */
    private CompletableFuture<Void> writeHomes(UUID player, Homes homes) {
        Optional<AtriumUser> cached = ctx.users().cached(player);
        if (cached.isPresent()) {
            try {
                cached.get().update(store.component, current -> homes);
                return ctx.users().saveNow(player);
            } catch (IllegalStateException e) {
                // Component still loading; fall through to a direct storage write.
            }
        }
        return ctx.users().load(player).thenCompose(found -> {
            if (found.isPresent()) {
                try {
                    found.get().update(store.component, current -> homes);
                    return ctx.users().saveNow(player);
                } catch (IllegalStateException e) {
                    ctx.logger().warn("Homes of {} are still not loaded; writing them directly", player, e);
                }
            }
            return store.component.saver().save(player, homes);
        });
    }

    // ---- helpers ----

    private Homes homesOf(Player player) {
        return ctx.users().user(player).get(store.component);
    }

    private SuggestionProvider<CommandSourceStack> homeSuggestions() {
        return (c, builder) -> {
            String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
            CommandSender sender = c.getSource().getSender();
            if (sender instanceof Player player) {
                try {
                    for (Home home : homesOf(player).list()) {
                        if (home.name().toLowerCase(Locale.ROOT).startsWith(typed)) {
                            builder.suggest(home.name());
                        }
                    }
                } catch (IllegalStateException e) {
                    // data still loading; no suggestions
                }
                String bed = config.get().bedHomeName;
                if (config.get().bedHome && player.hasPermission(PERM_BED) && bed.toLowerCase(Locale.ROOT).startsWith(typed)) {
                    builder.suggest(bed);
                }
            }
            if (sender.hasPermission(PERM_OTHERS) && !typed.isEmpty() && !typed.contains(":")) {
                for (String name : ctx.users().knownNames(typed, 10)) {
                    builder.suggest(name + ":");
                }
            }
            return builder.buildFuture();
        };
    }

    /** Loads another player's homes (also offline) and hands them to {@code action}; reports problems itself. */
    private void withOtherHomes(CommandSender sender, String owner, Consumer<OtherHomes> action) {
        ctx.users().loadByName(owner).whenComplete((found, error) -> {
            if (error != null) {
                ctx.logger().error("Loading homes of {} failed", owner, error);
                messages.send(sender, "core.error");
                return;
            }
            if (found.isEmpty()) {
                messages.send(sender, "core.user-not-found", Msg.unparsed("name", owner));
                return;
            }
            AtriumUser user = found.get();
            Homes homes;
            try {
                homes = user.get(store.component);
            } catch (IllegalStateException e) {
                ctx.logger().error("Homes of {} are not loaded", user.name(), e);
                messages.send(sender, "core.error");
                return;
            }
            try {
                action.accept(new OtherHomes(user, homes));
            } catch (RuntimeException e) {
                ctx.logger().error("Home command for {} failed", user.name(), e);
                messages.send(sender, "core.error");
            }
        });
    }

    private record OtherHomes(AtriumUser user, Homes homes) {}

    /**
     * Stores the owner's changed homes now and runs {@code confirm} only once the write succeeded. A failed
     * write is reported (the change stays in memory and the user service retries it later).
     */
    private void confirmSaved(CommandSender sender, java.util.UUID owner, String what, Runnable confirm) {
        ctx.users().saveNow(owner).whenComplete((v, error) -> {
            if (error != null) {
                ctx.logger().error("{}: saving homes of {} failed", what, owner, error);
                messages.send(sender, "homes.save-failed");
                return;
            }
            confirm.run();
        });
    }

    private boolean isSelf(CommandSender sender, String owner) {
        return sender instanceof Player p && p.getName().equalsIgnoreCase(owner);
    }

    // ---- /home ----

    private int home(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        String raw = Cmds.hasArgument(c, "spec") ? StringArgumentType.getString(c, "spec") : "";
        HomeSpec spec = HomeSpec.parse(raw).orElseThrow(() -> cmds.fail(c.getSource(), "homes.invalid-name"));
        HomesConfig cfg = config.get();

        if (spec.other() && !isSelf(self, spec.owner())) {
            if (!self.hasPermission(PERM_OTHERS)) {
                throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", PERM_OTHERS));
            }
            withOtherHomes(self, spec.owner(), other -> {
                if (spec.name() == null) {
                    sendList(self, other.user().name(), other.homes(), -1, null);
                    return;
                }
                Optional<Home> home = other.homes().get(spec.name());
                if (home.isEmpty()) {
                    messages.send(self, "homes.not-found-other", Msg.unparsed("player", other.user().name()),
                            Msg.unparsed("home", spec.name()));
                    return;
                }
                teleportTo(self, home.get(), spec.confirm(), other.user().name() + ":" + home.get().name());
            });
            return Cmds.OK;
        }

        Homes own = homesOf(self);
        String name = spec.name();
        if (name == null) {
            if (own.count() == 1) {
                name = own.list().getFirst().name();
            } else if (own.contains(cfg.defaultName)) {
                name = cfg.defaultName;
            } else if (own.count() == 0 && cfg.bedHome && self.hasPermission(PERM_BED) && self.getRespawnLocation(false) != null) {
                name = cfg.bedHomeName;
            } else {
                sendList(self, null, own, Permissions.limit(self, LIMIT_PREFIX, cfg.defaultLimit), self);
                return Cmds.OK;
            }
        }
        if (cfg.bedHome && name.equalsIgnoreCase(cfg.bedHomeName) && !own.contains(name)) {
            if (!self.hasPermission(PERM_BED)) {
                throw cmds.fail(c.getSource(), "homes.bed-no-permission");
            }
            Location bed = self.getRespawnLocation(false);
            if (bed == null) {
                throw cmds.fail(c.getSource(), "homes.no-bed");
            }
            TeleportOptions options = spec.confirm() ? TeleportOptions.DEFAULT.withSafety(TeleportOptions.Safety.SKIP)
                    : TeleportOptions.DEFAULT.withSafety(TeleportOptions.Safety.CHECK);
            String bedName = cfg.bedHomeName;
            teleport.teleport(self, bed, TeleportCause.HOME, options)
                    .thenAccept(result -> afterTeleport(self, result, bedName, bedName));
            return Cmds.OK;
        }
        String wanted = name;
        Home home = own.get(name).orElseThrow(() -> cmds.fail(c.getSource(), "homes.not-found", Msg.unparsed("home", wanted)));
        teleportTo(self, home, spec.confirm(), home.name());
        return Cmds.OK;
    }

    private void teleportTo(Player self, Home home, boolean confirm, String reference) {
        HomesConfig cfg = config.get();
        StoredLocation stored = home.location();
        if (cfg.blocked(cfg.noTeleportWorlds, stored.world()) && !self.hasPermission(PERM_BYPASS_WORLDS)) {
            messages.send(self, "homes.world-blocked", Msg.unparsed("world", stored.world()));
            return;
        }
        Optional<Location> target = stored.toLocation();
        TeleportOptions options = confirm ? TeleportOptions.DEFAULT.withSafety(TeleportOptions.Safety.SKIP) : TeleportOptions.DEFAULT;
        if (target.isEmpty()) {
            // The world may be an AWF/profile world that loads on demand (WorldResolver via the teleport module).
            var storedTeleports = ctx.services().get(dev.yanianz.atrium.api.teleport.StoredTeleportService.class);
            if (storedTeleports.isEmpty()) {
                messages.send(self, "homes.world-not-loaded", Msg.unparsed("world", stored.world()));
                return;
            }
            storedTeleports.get().teleport(self, stored, TeleportCause.HOME, options)
                    .thenAccept(result -> afterTeleport(self, result.result(), home.name(), reference));
            return;
        }
        teleport.teleport(self, target.get(), TeleportCause.HOME, options)
                .thenAccept(result -> afterTeleport(self, result, home.name(), reference));
    }

    private void afterTeleport(Player self, TeleportResult result, String name, String reference) {
        if (result == TeleportResult.SUCCESS) {
            messages.send(self, "homes.teleported", Msg.unparsed("home", name));
        } else if (result == TeleportResult.UNSAFE && config.get().offerUnsafeConfirm) {
            // reference is built from validated names only ([A-Za-z0-9_-] and ':'), safe inside a command.
            Component prompt = messages.render(self, "homes.unsafe-confirm", Msg.unparsed("home", name))
                    .clickEvent(ClickEvent.runCommand("/home " + reference + " confirm"))
                    .hoverEvent(HoverEvent.showText(messages.render(self, "homes.unsafe-confirm-hover")));
            self.sendMessage(prompt);
        }
    }

    // ---- /sethome ----

    private int setHome(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        HomesConfig cfg = config.get();
        String name = Cmds.hasArgument(c, "name") ? StringArgumentType.getString(c, "name") : cfg.defaultName;
        if (!Homes.validName(name)) {
            throw cmds.fail(c.getSource(), "homes.invalid-name");
        }
        if (cfg.bedHome && name.equalsIgnoreCase(cfg.bedHomeName)) {
            throw cmds.fail(c.getSource(), "homes.name-reserved", Msg.unparsed("home", name));
        }
        Location here = self.getLocation();
        String world = here.getWorld().getName();
        if (cfg.blocked(cfg.noSetWorlds, world) && !self.hasPermission(PERM_BYPASS_WORLDS)) {
            throw cmds.fail(c.getSource(), "homes.set-world-blocked", Msg.unparsed("world", world));
        }
        // The limit comes from the player who owns the home (the sender), read fresh every time.
        int limit = Permissions.limit(self, LIMIT_PREFIX, cfg.defaultLimit);
        Home home = new Home(name, StoredLocation.of(here), System.currentTimeMillis());
        AtomicBoolean replaced = new AtomicBoolean();
        AtomicBoolean allowed = new AtomicBoolean();
        ctx.users().user(self).update(store.component, homes -> {
            replaced.set(homes.contains(name));
            allowed.set(homes.canSet(name, limit));
            return allowed.get() ? homes.with(home) : homes;
        });
        if (!allowed.get()) {
            throw cmds.fail(c.getSource(), "homes.limit-reached", Msg.number("limit", limit));
        }
        String key = replaced.get() ? "homes.replaced" : "homes.set";
        confirmSaved(self, self.getUniqueId(), "/sethome " + name,
                () -> messages.send(self, key, Msg.unparsed("home", name), Msg.location("at", here)));
        return Cmds.OK;
    }

    // ---- /delhome ----

    /** A bare /delhome explains itself instead of failing silently. */
    private int usageDelhome(CommandContext<CommandSourceStack> c) {
        messages.send(c.getSource().getSender(), "homes.usage-delhome");
        return Cmds.OK;
    }

    private int delHome(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        HomeSpec spec = HomeSpec.parse(StringArgumentType.getString(c, "spec"))
                .filter(s -> s.name() != null)
                .orElseThrow(() -> cmds.fail(c.getSource(), "homes.invalid-name"));
        if (spec.other() && !isSelf(sender, spec.owner())) {
            if (!sender.hasPermission(PERM_OTHERS)) {
                throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", PERM_OTHERS));
            }
            withOtherHomes(sender, spec.owner(), other -> {
                AtomicBoolean removed = new AtomicBoolean();
                other.user().update(store.component, homes -> {
                    removed.set(homes.contains(spec.name()));
                    return homes.without(spec.name());
                });
                if (removed.get()) {
                    confirmSaved(sender, other.user().uuid(), "/delhome " + other.user().name() + ":" + spec.name(),
                            () -> messages.send(sender, "homes.deleted-other", Msg.unparsed("home", spec.name()),
                                    Msg.unparsed("player", other.user().name())));
                } else {
                    messages.send(sender, "homes.not-found-other", Msg.unparsed("home", spec.name()),
                            Msg.unparsed("player", other.user().name()));
                }
            });
            return Cmds.OK;
        }
        Player self = cmds.player(c.getSource());
        AtomicBoolean removed = new AtomicBoolean();
        ctx.users().user(self).update(store.component, homes -> {
            removed.set(homes.contains(spec.name()));
            return homes.without(spec.name());
        });
        if (!removed.get()) {
            throw cmds.fail(c.getSource(), "homes.not-found", Msg.unparsed("home", spec.name()));
        }
        confirmSaved(self, self.getUniqueId(), "/delhome " + spec.name(),
                () -> messages.send(self, "homes.deleted", Msg.unparsed("home", spec.name())));
        return Cmds.OK;
    }

    // ---- /homes ----

    private int listHomes(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        if (Cmds.hasArgument(c, "player")) {
            String owner = StringArgumentType.getString(c, "player");
            if (!isSelf(sender, owner)) {
                if (!HomeSpec.validPlayerName(owner)) {
                    throw cmds.fail(c.getSource(), "core.user-not-found", Msg.unparsed("name", owner));
                }
                withOtherHomes(sender, owner, other -> sendList(sender, other.user().name(), other.homes(), -1, null));
                return Cmds.OK;
            }
        }
        Player self = cmds.player(c.getSource());
        sendList(self, null, homesOf(self), Permissions.limit(self, LIMIT_PREFIX, config.get().defaultLimit), self);
        return Cmds.OK;
    }

    /**
     * Sends a clickable list.
     *
     * @param owner     null for the viewer's own homes
     * @param limit     shown limit, or -1 to hide it
     * @param bedOwner  player whose bed home is listed too, or null
     */
    private void sendList(CommandSender viewer, String owner, Homes homes, int limit, Player bedOwner) {
        HomesConfig cfg = config.get();
        List<Component> entries = new ArrayList<>();
        for (Home home : homes.list()) {
            String reference = owner == null ? home.name() : owner + ":" + home.name();
            StoredLocation l = home.location();
            Component hover = messages.render(viewer, "homes.list-hover", Msg.unparsed("home", home.name()),
                    Msg.unparsed("world", l.world()), Msg.number("x", l.blockX()), Msg.number("y", l.blockY()),
                    Msg.number("z", l.blockZ()));
            entries.add(messages.render(viewer, "homes.list-entry", Msg.unparsed("home", home.name()))
                    .clickEvent(ClickEvent.runCommand("/home " + reference))
                    .hoverEvent(HoverEvent.showText(hover)));
        }
        if (bedOwner != null && cfg.bedHome && bedOwner.hasPermission(PERM_BED) && bedOwner.getRespawnLocation(false) != null
                && !homes.contains(cfg.bedHomeName)) {
            entries.add(messages.render(viewer, "homes.list-entry-bed", Msg.unparsed("home", cfg.bedHomeName))
                    .clickEvent(ClickEvent.runCommand("/home " + cfg.bedHomeName)));
        }
        String limitText = limit < 0 ? "" : limit == Integer.MAX_VALUE ? "∞" : Integer.toString(limit);
        if (owner == null) {
            messages.send(viewer, "homes.list-header", Msg.number("count", homes.count()), Msg.unparsed("limit", limitText));
        } else {
            messages.send(viewer, "homes.list-header-other", Msg.number("count", homes.count()), Msg.unparsed("player", owner));
        }
        if (entries.isEmpty()) {
            messages.send(viewer, "homes.list-empty");
            return;
        }
        Component separator = messages.render(viewer, "homes.list-separator");
        viewer.sendMessage(Component.join(net.kyori.adventure.text.JoinConfiguration.separator(separator), entries));
    }
}
