package dev.yanianz.atrium.module.seen;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentService;
import dev.yanianz.atrium.api.playtime.PlaytimeService;
import dev.yanianz.atrium.api.ranks.RankService;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * {@code /seen <player>}, {@code /whois <player>} and {@code /playerlist} (also {@code /list}).
 *
 * <p>Players hidden by vanish are treated as offline for viewers who cannot see them: no online status, no
 * current location and no entry in the player list.
 */
public final class SeenModule implements AtriumModule, Listener {
    static final String PERM_SEEN = "atrium.seen";
    static final String PERM_LOCATION = "atrium.seen.location";
    static final String PERM_IP = "atrium.seen.ip";
    static final String PERM_ALTS = "atrium.seen.alts";
    static final String PERM_PUNISHMENTS = "atrium.seen.punishments";
    static final String PERM_WHOIS = "atrium.whois";
    static final String PERM_LIST = "atrium.playerlist";

    private static final String DEFAULT_DATE_FORMAT = "yyyy-MM-dd HH:mm";

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<SeenConfig> config;
    private SeenStore store;
    private volatile DateTimeFormatter dates;
    /** Join time of online players (since this module was enabled). */
    private final Map<UUID, Instant> sessions = new ConcurrentHashMap<>();

    /** Where a player is (current) or was when logging out. */
    private record Place(boolean current, String world, double x, double y, double z) {}

    /** Values read on the target's own thread. */
    private record Snapshot(String gamemode, double health, double maxHealth, int food, float saturation,
            boolean flying, boolean allowFlight, boolean op, int level, Place place, String locale, int ping,
            @Nullable String brand) {}

    @Override
    public String id() {
        return "seen";
    }

    @Override
    public String description() {
        return "Seen, whois and the grouped player list";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("vanish", "moderation", "playtime", "ranks");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(SeenConfig.class, SeenConfig::new);
        ctx.migrate(SeenStore.MIGRATIONS);
        this.store = new SeenStore(ctx.database());
        ctx.component(store.component);
        ctx.listen(this);
        loadFormatter();

        ctx.permission(PERM_SEEN, PermissionDefault.TRUE, "Look up when a player was last online");
        ctx.permission(PERM_LOCATION, PermissionDefault.OP, "See the current or logout location in /seen");
        ctx.permission(PERM_IP, PermissionDefault.OP, "See the last address in /seen and /whois");
        ctx.permission(PERM_ALTS, PermissionDefault.OP, "See other accounts of the same address in /seen");
        ctx.permission(PERM_PUNISHMENTS, PermissionDefault.OP, "See active punishments in /seen");
        ctx.permission(PERM_WHOIS, PermissionDefault.OP, "Show detailed information about an online player");
        ctx.permission(PERM_LIST, PermissionDefault.TRUE, "List online players by group");

        ctx.command(CommandSpec.of(Cmds.literal("seen", PERM_SEEN)
                .executes(context -> usage(context, "seen.usage-seen"))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(Cmds.knownPlayers(ctx.users()))
                        .executes(this::seen))
                .build(), "Show when a player was last online", "lastseen"));
        ctx.command(CommandSpec.of(Cmds.literal("whois", PERM_WHOIS)
                .executes(context -> usage(context, "seen.usage-whois"))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::whois))
                .build(), "Show detailed information about an online player"));
        ctx.command(CommandSpec.of(listNode("playerlist"), "List online players by group", "online"));
        if (config.get().replaceListCommand) {
            ctx.command(CommandSpec.of(listNode("list"), "List online players by group"));
        }
        Instant now = Instant.now();
        Bukkit.getOnlinePlayers().forEach(p -> sessions.putIfAbsent(p.getUniqueId(), now));
    }

    private LiteralCommandNode<CommandSourceStack> listNode(String name) {
        return Cmds.literal(name, PERM_LIST).executes(this::list).build();
    }

    private int usage(CommandContext<CommandSourceStack> c, String key) {
        ctx.messages().send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    @Override
    public void reload() {
        loadFormatter();
    }

    private void loadFormatter() {
        SeenConfig cfg = config.get();
        try {
            dates = SeenFormat.formatter(cfg.dateFormat, cfg.timeZone);
        } catch (IllegalArgumentException e) {
            ctx.logger().warn("Invalid dateFormat '{}' or timeZone '{}' in modules/seen.yml; using {}", cfg.dateFormat,
                    cfg.timeZone, DEFAULT_DATE_FORMAT, e);
            dates = SeenFormat.formatter(DEFAULT_DATE_FORMAT, "");
        }
    }

    // ---- events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        sessions.put(event.getPlayer().getUniqueId(), Instant.now());
    }

    /** Runs on the player's thread before the core saves the user (MONITOR), so the location is stored with it. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        sessions.remove(player.getUniqueId());
        LogoutLocation location = LogoutLocation.of(player.getLocation(), System.currentTimeMillis());
        if (!location.isKnown()) {
            return;
        }
        ctx.users().cached(player.getUniqueId()).ifPresent(user -> {
            try {
                user.update(store.component, old -> location);
            } catch (IllegalStateException e) {
                ctx.logger().warn("Could not record the logout location of {}", player.getName(), e);
            }
        });
    }

    // ---- /seen ----

    private int seen(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        String name = StringArgumentType.getString(c, "player");
        ctx.users().resolveAccount(name, false)
                .thenCompose(ref -> ref.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<AtriumUser>empty())
                        : ctx.users().load(ref.get().uuid()))
                .whenComplete((user, error) -> {
                    if (error != null) {
                        ctx.logger().warn("Looking up {} for /seen failed", name, error);
                        send(sender, "core.error");
                    } else if (user.isEmpty()) {
                        send(sender, "core.user-not-found", Msg.unparsed("name", name));
                    } else {
                        seen(sender, user.get());
                    }
                });
        return Cmds.OK;
    }

    private void seen(CommandSender sender, AtriumUser user) {
        UUID uuid = user.uuid();
        Optional<VanishService> vanish = ctx.services().get(VanishService.class);
        Player online = Bukkit.getPlayer(uuid);
        boolean visible = online != null && canSee(sender, online, vanish);
        boolean vanished = visible && vanish.map(v -> v.isVanished(uuid)).orElse(false);

        CompletableFuture<Optional<Place>> place;
        if (!sender.hasPermission(PERM_LOCATION)) {
            place = CompletableFuture.completedFuture(Optional.empty());
        } else if (visible) {
            place = ctx.scheduler().supplyOnEntity(online, () -> Optional.of(current(online.getLocation())))
                    .exceptionally(t -> logoutPlace(user)); // left meanwhile: the logout location was just stored
        } else {
            place = CompletableFuture.completedFuture(logoutPlace(user));
        }
        Optional<String> address = user.lastAddress();
        CompletableFuture<Optional<List<String>>> alts = sender.hasPermission(PERM_ALTS) && address.isPresent()
                ? ctx.users().accountsByAddress(address.get())
                        .thenApply(list -> Optional.of(SeenFormat.otherNames(list, uuid)))
                : CompletableFuture.completedFuture(Optional.empty());
        Optional<PunishmentService> punishmentService = sender.hasPermission(PERM_PUNISHMENTS)
                ? ctx.services().get(PunishmentService.class) : Optional.empty();
        CompletableFuture<Optional<List<Punishment>>> punishments = punishmentService
                .map(s -> s.active(uuid).thenApply(Optional::of))
                .orElse(CompletableFuture.completedFuture(Optional.empty()));

        CompletableFuture.allOf(place, alts, punishments).whenComplete((v, error) -> onSender(sender, () -> {
            if (error != null) {
                ctx.logger().warn("Collecting /seen data of {} failed", user.name(), error);
                ctx.messages().send(sender, "core.error");
                return;
            }
            Instant now = Instant.now();
            TagResolver player = Msg.unparsed("player", user.name());
            ctx.messages().send(sender, "seen.header", player);
            if (visible) {
                Instant since = sessions.get(uuid);
                if (since != null) {
                    ctx.messages().send(sender, "seen.online", player, Msg.duration("duration", SeenFormat.since(since, now)));
                } else {
                    ctx.messages().send(sender, "seen.online-unknown", player);
                }
                if (vanished) {
                    ctx.messages().send(sender, "seen.vanished");
                }
            } else {
                ctx.messages().send(sender, "seen.offline", player, Msg.duration("ago", SeenFormat.since(user.lastSeen(), now)),
                        Msg.unparsed("date", SeenFormat.absolute(user.lastSeen(), dates)));
            }
            ctx.messages().send(sender, "seen.first-join", Msg.unparsed("date", SeenFormat.absolute(user.firstJoin(), dates)),
                    Msg.duration("ago", SeenFormat.since(user.firstJoin(), now)));
            if (sender.hasPermission(PERM_LOCATION)) {
                sendPlace(sender, place.join().orElse(null));
            }
            if (sender.hasPermission(PERM_IP)) {
                address.ifPresentOrElse(ip -> ctx.messages().send(sender, "seen.ip", Msg.unparsed("ip", ip)),
                        () -> ctx.messages().send(sender, "seen.ip-unknown"));
            }
            alts.join().ifPresent(names -> sendAlts(sender, names));
            punishments.join().ifPresent(list -> sendPunishments(sender, list, now));
        }));
    }

    private static Place current(Location location) {
        String world = location.getWorld() == null ? "?" : location.getWorld().getName();
        return new Place(true, world, location.getX(), location.getY(), location.getZ());
    }

    private Optional<Place> logoutPlace(AtriumUser user) {
        try {
            LogoutLocation logout = user.get(store.component);
            return logout.isKnown()
                    ? Optional.of(new Place(false, logout.world(), logout.x(), logout.y(), logout.z()))
                    : Optional.empty();
        } catch (IllegalStateException notLoaded) {
            return Optional.empty();
        }
    }

    private void sendPlace(CommandSender sender, @Nullable Place place) {
        if (place == null) {
            ctx.messages().send(sender, "seen.location-unknown");
            return;
        }
        ctx.messages().send(sender, place.current() ? "seen.location" : "seen.location-logout",
                Msg.unparsed("world", place.world()),
                Msg.number("x", (long) Math.floor(place.x())),
                Msg.number("y", (long) Math.floor(place.y())),
                Msg.number("z", (long) Math.floor(place.z())));
    }

    private void sendAlts(CommandSender sender, List<String> names) {
        if (names.isEmpty()) {
            ctx.messages().send(sender, "seen.alts-none");
        } else {
            ctx.messages().send(sender, "seen.alts", Msg.number("count", names.size()),
                    Msg.unparsed("alts", String.join(", ", names)));
        }
    }

    private void sendPunishments(CommandSender sender, List<Punishment> list, Instant now) {
        List<Punishment> active = list.stream().filter(p -> p.isActive(now)).toList();
        if (active.isEmpty()) {
            ctx.messages().send(sender, "seen.punishments-none");
            return;
        }
        ctx.messages().send(sender, "seen.punishments", Msg.number("count", active.size()));
        for (Punishment p : active) {
            Component remaining = p.remaining(now)
                    .map(d -> ctx.messages().render(sender, "seen.remaining", Msg.duration("time", d)))
                    .orElseGet(() -> ctx.messages().render(sender, "seen.permanent"));
            ctx.messages().send(sender, "seen.punishment",
                    Msg.unparsed("type", p.type().name().toLowerCase(Locale.ROOT)),
                    Msg.unparsed("actor", p.actorName()),
                    Msg.unparsed("reason", p.reason()),
                    Msg.component("remaining", remaining));
        }
    }

    // ---- /whois ----

    private int whois(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player target = cmds.argPlayer(c, "target");
        Optional<VanishService> vanish = ctx.services().get(VanishService.class);
        if (!canSee(sender, target, vanish)) {
            throw cmds.fail(c.getSource(), "core.player-not-found");
        }
        UUID uuid = target.getUniqueId();
        String name = target.getName();
        boolean vanished = vanish.map(v -> v.isVanished(uuid)).orElse(false);
        Optional<AtriumUser> user = ctx.users().cached(uuid);

        CompletableFuture<Snapshot> snapshot = ctx.scheduler().supplyOnEntity(target, () -> snapshot(target));
        CompletableFuture<Optional<Long>> playtime = ctx.services().get(PlaytimeService.class)
                .map(s -> s.totalPlaytime(uuid).thenApply(Optional::of).exceptionally(t -> {
                    ctx.logger().warn("Reading the playtime of {} for /whois failed", name, t);
                    return Optional.empty();
                }))
                .orElse(CompletableFuture.completedFuture(Optional.empty()));
        CompletableFuture<Optional<String>> rank = ctx.services().get(RankService.class)
                .map(s -> s.currentRank(uuid).exceptionally(t -> {
                    ctx.logger().warn("Reading the rank of {} for /whois failed", name, t);
                    return Optional.empty();
                }))
                .orElse(CompletableFuture.completedFuture(Optional.empty()));

        CompletableFuture.allOf(snapshot, playtime, rank).whenComplete((v, error) -> onSender(sender, () -> {
            if (error != null) {
                if (rootCause(error) instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException) {
                    ctx.messages().send(sender, "seen.whois-left", Msg.unparsed("player", name));
                } else {
                    ctx.logger().warn("Collecting /whois data of {} failed", name, error);
                    ctx.messages().send(sender, "core.error");
                }
                return;
            }
            Snapshot s = snapshot.join();
            ctx.messages().send(sender, "seen.whois-header", Msg.unparsed("player", name));
            ctx.messages().send(sender, "seen.whois-uuid", Msg.unparsed("uuid", uuid.toString()));
            ctx.messages().send(sender, "seen.whois-health",
                    Msg.unparsed("health", oneDecimal(s.health())),
                    Msg.unparsed("max", oneDecimal(s.maxHealth())),
                    Msg.number("food", s.food()),
                    Msg.unparsed("saturation", oneDecimal(s.saturation())));
            ctx.messages().send(sender, "seen.whois-state",
                    Msg.unparsed("gamemode", s.gamemode()),
                    Msg.component("flying", yesNo(sender, s.flying())),
                    Msg.component("allow_flight", yesNo(sender, s.allowFlight())),
                    Msg.component("op", yesNo(sender, s.op())),
                    Msg.component("vanished", yesNo(sender, vanished)));
            ctx.messages().send(sender, "seen.whois-level", Msg.number("level", s.level()));
            sendPlace(sender, s.place());
            ctx.messages().send(sender, "seen.whois-client",
                    Msg.unparsed("locale", s.locale()),
                    Msg.number("ping", s.ping()),
                    Msg.unparsed("brand", s.brand() == null ? "?" : s.brand()));
            Instant now = Instant.now();
            Instant since = sessions.get(uuid);
            if (since != null) {
                ctx.messages().send(sender, "seen.online", Msg.unparsed("player", name),
                        Msg.duration("duration", SeenFormat.since(since, now)));
            }
            user.ifPresent(u -> ctx.messages().send(sender, "seen.first-join",
                    Msg.unparsed("date", SeenFormat.absolute(u.firstJoin(), dates)),
                    Msg.duration("ago", SeenFormat.since(u.firstJoin(), now))));
            playtime.join().ifPresent(seconds -> ctx.messages().send(sender, "seen.whois-playtime",
                    Msg.duration("time", Duration.ofSeconds(seconds))));
            rank.join().ifPresent(id -> ctx.messages().send(sender, "seen.whois-rank", Msg.unparsed("rank", id)));
            if (sender.hasPermission(PERM_IP)) {
                user.flatMap(AtriumUser::lastAddress).ifPresentOrElse(
                        ip -> ctx.messages().send(sender, "seen.ip", Msg.unparsed("ip", ip)),
                        () -> ctx.messages().send(sender, "seen.ip-unknown"));
            }
        }));
        return Cmds.OK;
    }

    /** Runs on the target's thread. */
    private static Snapshot snapshot(Player p) {
        var maxHealth = p.getAttribute(Attribute.MAX_HEALTH);
        return new Snapshot(p.getGameMode().name().toLowerCase(Locale.ROOT), p.getHealth(),
                maxHealth == null ? 20.0 : maxHealth.getValue(), p.getFoodLevel(), p.getSaturation(), p.isFlying(),
                p.getAllowFlight(), p.isOp(), p.getLevel(), current(p.getLocation()), p.locale().toString(), p.getPing(),
                p.getClientBrandName());
    }

    private Component yesNo(CommandSender sender, boolean value) {
        return ctx.messages().render(sender, value ? "seen.flag-yes" : "seen.flag-no");
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t;
    }

    // ---- /playerlist ----

    private int list(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Optional<VanishService> vanish = ctx.services().get(VanishService.class);
        List<Player> visible = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (canSee(sender, player, vanish)) {
                visible.add(player);
            }
        }
        if (visible.isEmpty()) {
            ctx.messages().send(sender, "seen.list-empty", Msg.number("max", Bukkit.getMaxPlayers()));
            return Cmds.OK;
        }
        ctx.messages().send(sender, "seen.list-header", Msg.number("count", visible.size()),
                Msg.number("max", Bukkit.getMaxPlayers()));
        Component separator = ctx.messages().render(sender, "seen.list-separator");
        for (SeenFormat.Group<Player> group : SeenFormat.group(config.get().groups, visible, Player::hasPermission,
                Player::getName)) {
            List<Component> names = new ArrayList<>();
            for (Player member : group.members()) {
                boolean hidden = vanish.map(v -> v.isVanished(member.getUniqueId())).orElse(false);
                names.add(ctx.messages().render(sender, hidden ? "seen.list-player-vanished" : "seen.list-player",
                        Msg.unparsed("player", member.getName())));
            }
            Component title = group.group() == null
                    ? ctx.messages().render(sender, "seen.list-other")
                    : ctx.messages().parseTrusted(group.group().display);
            ctx.messages().send(sender, "seen.list-group", Msg.component("group", title),
                    Msg.number("count", group.members().size()),
                    Msg.component("players", Component.join(net.kyori.adventure.text.JoinConfiguration.separator(separator),
                            names)));
        }
        return Cmds.OK;
    }

    // ---- helpers ----

    private static boolean canSee(CommandSender sender, Player target, Optional<VanishService> vanish) {
        return !(sender instanceof Player viewer) || vanish.map(v -> v.canSee(viewer, target)).orElse(true);
    }

    private void onSender(CommandSender sender, Runnable work) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, work, null);
        } else {
            ctx.scheduler().global(work);
        }
    }

    private void send(CommandSender sender, String key, TagResolver... tags) {
        onSender(sender, () -> ctx.messages().send(sender, key, tags));
    }
}
