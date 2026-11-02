package dev.yanianz.atrium.module.teleport;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
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
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** /tp, /tphere, /tpall, /top, /back. */
final class TeleportCommands {
    static final String PERM_TP = "atrium.tp";
    static final String PERM_TP_OTHERS = "atrium.tp.others";
    static final String PERM_TPHERE = "atrium.tphere";
    static final String PERM_TPALL = "atrium.tpall";
    static final String PERM_TOP = "atrium.top";
    static final String PERM_BACK = "atrium.back";
    static final String PERM_BACK_OTHERS = "atrium.back.others";
    static final String PERM_DBACK = "atrium.dback";
    static final String PERM_DBACK_OTHERS = "atrium.dback.others";
    static final String PERM_RESETBACK = "atrium.resetback";
    static final String PERM_RESETBACK_OTHERS = "atrium.resetback.others";
    static final String PERM_TOGGLE_BYPASS = "atrium.tptoggle.bypass";

    /** Player names are [A-Za-z0-9_]{1,16}; anything else cannot be a stored account. */
    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final Messages messages;
    private final TeleportServiceImpl service;
    private final ConfigHandle<TeleportConfig> config;

    TeleportCommands(ModuleContext ctx, TeleportServiceImpl service, ConfigHandle<TeleportConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.service = service;
        this.config = config;
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("tp", PERM_TP)
                .executes(c -> usage(c, "teleport.usage-tp"))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::tpToPlayer)
                        .then(Commands.argument("destination", ArgumentTypes.player()).executes(this::tpOtherToPlayer))
                        .then(Commands.argument("position", new PositionArgument()).executes(this::tpOtherToPosition)
                                .then(Commands.argument("world", ArgumentTypes.world()).executes(this::tpOtherToPosition))))
                .then(Commands.argument("position", new PositionArgument()).executes(this::tpToPosition)
                        .then(Commands.argument("world", ArgumentTypes.world()).executes(this::tpToPosition)))
                .build(), "Teleport to a player or to coordinates", "teleport"));
        ctx.command(CommandSpec.of(Cmds.literal("tphere", PERM_TPHERE)
                .executes(c -> usage(c, "teleport.usage-tphere"))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(this::tpHere))
                .build(), "Teleport a player to you"));
        ctx.command(CommandSpec.of(Cmds.literal("tpall", PERM_TPALL)
                .executes(this::tpAll)
                .build(), "Teleport every player to you"));
        ctx.command(CommandSpec.of(Cmds.literal("top", PERM_TOP)
                .executes(this::top)
                .build(), "Teleport to the highest safe block above you"));
        // "list" is declared before the string target and "n" before "target", so a literal or a plain
        // number is never read as a player name. The target is a name (not a selector) so offline players
        // can be listed with /back <player> list.
        ctx.command(CommandSpec.of(Cmds.literal("back", PERM_BACK)
                .executes(c -> back(c, null, 1))
                .then(Cmds.literal("list")
                        .executes(c -> listBack(c, null))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(backTargets(PERM_BACK_OTHERS))
                                .executes(c -> listBack(c, StringArgumentType.getString(c, "target")))))
                .then(Commands.argument("n", IntegerArgumentType.integer(1, BackHistory.MAX_CAPACITY))
                        .executes(c -> back(c, null, IntegerArgumentType.getInteger(c, "n"))))
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(backTargets(PERM_BACK_OTHERS))
                        .executes(c -> back(c, StringArgumentType.getString(c, "target"), 1))
                        .then(Cmds.literal("list")
                                .executes(c -> listBack(c, StringArgumentType.getString(c, "target"))))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, BackHistory.MAX_CAPACITY))
                                .executes(c -> back(c, StringArgumentType.getString(c, "target"),
                                        IntegerArgumentType.getInteger(c, "n")))))
                .build(), "Return to a previous location: /back [n], /back list, /back <player> [n|list]"));
        ctx.command(CommandSpec.of(Cmds.literal("dback", PERM_DBACK)
                .executes(c -> back(c, null, 1, true))
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(backTargets(PERM_DBACK_OTHERS))
                        .executes(c -> back(c, StringArgumentType.getString(c, "target"), 1, true)))
                .build(), "Return to your previous location without the safety check",
                "backunsafe"));
        ctx.command(CommandSpec.of(Cmds.literal("resetback", PERM_RESETBACK)
                .executes(c -> resetBack(c, null))
                .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(backTargets(PERM_RESETBACK_OTHERS))
                        .executes(c -> resetBack(c, StringArgumentType.getString(c, "target"))))
                .build(), "Forget the /back history of a player"));
    }

    /** A bare root command explains itself instead of failing silently. */
    private int usage(CommandContext<CommandSourceStack> c, String key) {
        messages.send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    /** Suggests known players only to staff who may act on someone else. */
    private SuggestionProvider<CommandSourceStack> backTargets(String othersPermission) {
        return (c, builder) -> {
            if (!c.getSource().getSender().hasPermission(othersPermission)) {
                return builder.buildFuture();
            }
            String typed = builder.getRemainingLowerCase();
            Set<String> seen = new HashSet<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(typed) && seen.add(online.getName())) {
                    builder.suggest(online.getName());
                }
            }
            for (String name : ctx.users().knownNames(typed, 30)) {
                if (seen.add(name)) {
                    builder.suggest(name);
                }
            }
            return builder.buildFuture();
        };
    }

    // ---- /tp ----

    private int tpToPlayer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Player target = cmds.argPlayer(c, "target");
        if (target.equals(self)) {
            throw cmds.fail(c.getSource(), "teleport.cannot-self");
        }
        checkAccepts(c, self, target);
        moveToPlayer(self, self, target);
        return Cmds.OK;
    }

    /**
     * /tp &lt;player&gt; &lt;destination&gt;. When the first argument is the sender ({@code /tp <you> <player>})
     * the two are swapped, so the other player is brought to the sender; moving someone else always needs
     * {@code atrium.tp.others}.
     */
    private int tpOtherToPlayer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        Player first = cmds.argPlayer(c, "target");
        Player second = cmds.argPlayer(c, "destination");
        if (first.equals(second)) {
            throw cmds.fail(c.getSource(), "teleport.cannot-self");
        }
        Player who = first;
        Player destination = second;
        if (sender instanceof Player self && first.equals(self)) {
            who = second;
            destination = first;
        }
        requireOthers(c, who);
        checkAccepts(c, sender, destination);
        moveToPlayer(sender, who, destination);
        return Cmds.OK;
    }

    /** Refuses /tp to a player who used /tptoggle, unless the sender may bypass it. */
    private void checkAccepts(CommandContext<CommandSourceStack> c, CommandSender sender, Player target)
            throws CommandSyntaxException {
        boolean accepts = service.state(target).map(TeleportState::acceptsRequests).orElse(true);
        if (!accepts && !sender.hasPermission(PERM_TOGGLE_BYPASS) && !sender.equals(target)) {
            throw cmds.fail(c.getSource(), "teleport.target-toggled", Msg.unparsed("player", target.getName()));
        }
    }

    /** A player may always move themselves; anyone else needs the "others" permission. */
    private void requireOthers(CommandContext<CommandSourceStack> c, Player who) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        if (!who.equals(sender) && !sender.hasPermission(PERM_TP_OTHERS)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", PERM_TP_OTHERS));
        }
    }

    private void moveToPlayer(CommandSender sender, Player who, Player destination) {
        locationOf(destination).thenCompose(loc -> service.teleport(who, loc, TeleportCause.COMMAND, TeleportOptions.IMMEDIATE))
                .whenComplete((result, error) -> {
                    if (error != null) {
                        messages.send(sender, "core.player-not-found");
                        return;
                    }
                    if (result != TeleportResult.SUCCESS) {
                        return;
                    }
                    messages.send(who, "teleport.tp-to-player", Msg.unparsed("player", destination.getName()));
                    if (!sender.equals(who)) {
                        messages.send(sender, "teleport.tp-other-to-player", Msg.unparsed("player", who.getName()),
                                Msg.unparsed("target", destination.getName()));
                    }
                });
    }

    private int tpToPosition(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        moveToPosition(c, self, self);
        return Cmds.OK;
    }

    private int tpOtherToPosition(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player who = cmds.argPlayer(c, "target");
        requireOthers(c, who);
        moveToPosition(c, c.getSource().getSender(), who);
        return Cmds.OK;
    }

    private void moveToPosition(CommandContext<CommandSourceStack> c, CommandSender sender, Player who) {
        Position position = c.getArgument("position", Position.class);
        World world = Cmds.hasArgument(c, "world") ? c.getArgument("world", World.class) : null;
        // Relative coordinates are relative to the player who moves, read on that player's thread.
        locationOf(who).thenCompose(base -> {
            Location target = position.resolve(base, world);
            if (!RelativeCoordinate.withinHorizontalLimit(target.getX()) || !RelativeCoordinate.withinHorizontalLimit(target.getZ())
                    || target.getY() < target.getWorld().getMinHeight() - 64 || target.getY() > target.getWorld().getMaxHeight() + 512) {
                messages.send(sender, "teleport.position-out-of-range");
                return CompletableFuture.completedFuture(TeleportResult.FAILED);
            }
            return service.teleport(who, target, TeleportCause.COMMAND, TeleportOptions.IMMEDIATE)
                    .thenApply(result -> {
                        if (result == TeleportResult.SUCCESS) {
                            var placeholders = Msg.location("to", target);
                            messages.send(who, "teleport.tp-to-position", placeholders);
                            if (!sender.equals(who)) {
                                messages.send(sender, "teleport.tp-other-to-position", placeholders,
                                        Msg.unparsed("player", who.getName()));
                            }
                        }
                        return result;
                    });
        }).exceptionally(error -> {
            messages.send(sender, "core.player-not-found");
            return TeleportResult.FAILED;
        });
    }

    // ---- /tphere, /tpall ----

    private int tpHere(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Player target = cmds.argPlayer(c, "target");
        if (target.equals(self)) {
            throw cmds.fail(c.getSource(), "teleport.cannot-self");
        }
        checkAccepts(c, self, target);
        Location here = self.getLocation();
        service.teleport(target, here, TeleportCause.COMMAND, TeleportOptions.IMMEDIATE).thenAccept(result -> {
            if (result == TeleportResult.SUCCESS) {
                messages.send(self, "teleport.tphere", Msg.unparsed("player", target.getName()));
                messages.send(target, "teleport.tphere-target", Msg.unparsed("player", self.getName()));
            }
        });
        return Cmds.OK;
    }

    private int tpAll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Location here = self.getLocation();
        int count = 0;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(self)) {
                continue;
            }
            count++;
            service.teleport(other, here, TeleportCause.COMMAND, TeleportOptions.IMMEDIATE).thenAccept(result -> {
                if (result == TeleportResult.SUCCESS) {
                    messages.send(other, "teleport.tphere-target", Msg.unparsed("player", self.getName()));
                }
            });
        }
        messages.send(self, "teleport.tpall", Msg.number("count", count));
        return Cmds.OK;
    }

    // ---- /top ----

    private int top(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Location at = self.getLocation();
        service.landing().top(at, config.get().safeLanding).whenComplete((spot, error) -> {
            if (error != null) {
                ctx.logger().error("/top search failed for {}", self.getName(), error);
                messages.send(self, "core.error");
                return;
            }
            if (spot.isEmpty() || spot.get().getBlockY() <= at.getBlockY()) {
                messages.send(self, "teleport.top-none");
                return;
            }
            service.teleport(self, spot.get(), TeleportCause.COMMAND, TeleportOptions.DEFAULT.withSafety(TeleportOptions.Safety.SKIP))
                    .thenAccept(result -> {
                        if (result == TeleportResult.SUCCESS) {
                            messages.send(self, "teleport.top");
                        }
                    });
        });
        return Cmds.OK;
    }

    // ---- /back ----

    /** A /back target: the sender, or a player resolved by name (online or loaded from storage). */
    private record BackTarget(UUID uuid, String name, AtriumUser user, @Nullable Player online, boolean self) {}

    /** /back [n] and /back <player> [n]: entry n of the history, 1 = newest. */
    private int back(CommandContext<CommandSourceStack> c, @Nullable String targetName, int n)
            throws CommandSyntaxException {
        return back(c, targetName, n, false);
    }

    /**
     * /back (safety checked) and /dback ({@code unsafe}: the exact stored spot, so lava or void is possible).
     */
    private int back(CommandContext<CommandSourceStack> c, @Nullable String targetName, int n, boolean unsafe)
            throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        withBackTarget(c, targetName, unsafe ? PERM_DBACK_OTHERS : PERM_BACK_OTHERS,
                target -> sendBack(sender, target, n, unsafe));
        return Cmds.OK;
    }

    /** /back list and /back <player> list. */
    private int listBack(CommandContext<CommandSourceStack> c, @Nullable String targetName) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        withBackTarget(c, targetName, PERM_BACK_OTHERS, target -> sendBackList(sender, target));
        return Cmds.OK;
    }

    /** /resetback [player]: drops the whole history, including death locations. */
    private int resetBack(CommandContext<CommandSourceStack> c, @Nullable String targetName)
            throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        withBackTarget(c, targetName, PERM_RESETBACK_OTHERS, target -> service.clearBack(target.uuid())
                .whenComplete((cleared, error) -> {
                    if (error != null) {
                        ctx.logger().error("Forgetting the /back history of {} failed", target.name(), error);
                        reply(sender, "core.error");
                        return;
                    }
                    if (!Boolean.TRUE.equals(cleared)) {
                        reply(sender, target.self() ? "teleport.back-none" : "teleport.back-none-other",
                                Msg.unparsed("player", target.name()));
                        return;
                    }
                    reply(sender, target.self() ? "teleport.resetback" : "teleport.resetback-other",
                            Msg.unparsed("player", target.name()));
                }));
        return Cmds.OK;
    }

    private void sendBack(CommandSender sender, BackTarget target, int n, boolean unsafe) {
        BackHistory history = service.historyOf(target.user());
        if (history.isEmpty()) {
            reply(sender, target.self() ? "teleport.back-none" : "teleport.back-none-other",
                    Msg.unparsed("player", target.name()));
            return;
        }
        Optional<BackHistory.Entry> found = history.get(n);
        if (found.isEmpty()) {
            reply(sender, "teleport.back-index", Msg.number("count", history.size()));
            return;
        }
        BackHistory.Entry entry = found.get();
        if (entry.death() && !sender.hasPermission(TeleportServiceImpl.PERM_BACK_ON_DEATH)) {
            reply(sender, "teleport.back-death-no-permission");
            return;
        }
        Player online = target.online();
        if (online == null) {
            reply(sender, "teleport.target-offline", Msg.unparsed("player", target.name()));
            return;
        }
        StoredLocation stored = entry.location();
        Optional<Location> destination = stored.toLocation();
        TeleportCause cause = entry.death() ? TeleportCause.DEATH_BACK : TeleportCause.BACK;
        // Staff sending someone back: no warmup or cooldown for the target. /dback never checks safety.
        TeleportOptions options = unsafe
                ? TeleportOptions.IMMEDIATE.withSafety(TeleportOptions.Safety.SKIP)
                : target.self() ? TeleportOptions.DEFAULT : TeleportOptions.IMMEDIATE;
        java.util.concurrent.CompletableFuture<TeleportResult> move;
        if (destination.isPresent()) {
            move = service.teleport(online, destination.get(), cause, options);
        } else {
            // World not loaded: an AWF/profile world may be loaded on demand (WorldResolver), with a reason on failure.
            var storedTeleports = ctx.services().get(dev.yanianz.atrium.api.teleport.StoredTeleportService.class);
            if (storedTeleports.isEmpty()) {
                reply(sender, "teleport.world-not-loaded", Msg.unparsed("world", stored.world()));
                return;
            }
            move = storedTeleports.get().teleport(online, stored, cause, options)
                    .thenApply(dev.yanianz.atrium.api.teleport.StoredTeleportService.StoredTeleportResult::result);
        }
        move.thenAccept(result -> {
            if (result != TeleportResult.SUCCESS) {
                return;
            }
            service.forgetBack(target.uuid(), entry);
            reply(online, entry.death() ? "teleport.back-death" : "teleport.back");
            if (!target.self()) {
                reply(sender, "teleport.back-other", Msg.unparsed("player", target.name()));
            }
        });
    }

    /** Newest first, each line clickable to /back that entry. Rendering happens on the sender's thread. */
    private void sendBackList(CommandSender sender, BackTarget target) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> sendBackListNow(player, target), null);
        } else {
            sendBackListNow(sender, target);
        }
    }

    private void sendBackListNow(CommandSender sender, BackTarget target) {
        BackHistory history = service.historyOf(target.user());
        if (history.isEmpty()) {
            reply(sender, target.self() ? "teleport.back-none" : "teleport.back-none-other",
                    Msg.unparsed("player", target.name()));
            return;
        }
        if (target.self()) {
            reply(sender, "teleport.back-list-header", Msg.number("count", history.size()));
        } else {
            reply(sender, "teleport.back-list-header-other", Msg.number("count", history.size()),
                    Msg.unparsed("player", target.name()));
        }
        long now = System.currentTimeMillis();
        boolean safeName = PLAYER_NAME.matcher(target.name()).matches();
        for (int i = 1; i <= history.size(); i++) {
            int index = i;
            BackHistory.Entry entry = history.get(i).orElseThrow();
            StoredLocation location = entry.location();
            Component kind = messages.render(sender, entry.death() ? "teleport.back-list-death"
                    : "teleport.back-list-teleport");
            Component line = messages.render(sender, "teleport.back-list-entry",
                    Msg.number("index", index),
                    Msg.component("kind", kind),
                    Msg.unparsed("world", location.world()),
                    Msg.number("x", location.blockX()),
                    Msg.number("y", location.blockY()),
                    Msg.number("z", location.blockZ()),
                    Msg.duration("age", Duration.ofMillis(Math.max(0, now - entry.recordedAt()))));
            if (safeName) {
                // Built from a validated name and a number only: never from player-typed text.
                String command = target.self() ? "/back " + index : "/back " + target.name() + " " + index;
                line = line.clickEvent(ClickEvent.runCommand(command))
                        .hoverEvent(HoverEvent.showText(messages.render(sender, "teleport.back-list-hover")));
            }
            sender.sendMessage(line);
        }
    }

    /**
     * Resolves the sender (or a name, online or offline) and hands the loaded user to {@code action}.
     * Offline players are loaded through the user service; only going /back needs them online.
     */
    private void withBackTarget(CommandContext<CommandSourceStack> c, @Nullable String rawName,
            String othersPermission, Consumer<BackTarget> action) throws CommandSyntaxException {
        CommandSender sender = c.getSource().getSender();
        if (rawName == null) {
            action.accept(selfTarget(c.getSource(), cmds.player(c.getSource())));
            return;
        }
        if (sender instanceof Player self && self.getName().equalsIgnoreCase(rawName)) {
            action.accept(selfTarget(c.getSource(), self));
            return;
        }
        if (!sender.hasPermission(othersPermission)) {
            throw cmds.fail(c.getSource(), "core.no-permission-others", Msg.unparsed("permission", othersPermission));
        }
        if (!PLAYER_NAME.matcher(rawName).matches()) {
            throw cmds.fail(c.getSource(), "core.user-not-found", Msg.unparsed("name", rawName));
        }
        ctx.users().resolveAccount(rawName, false).thenCompose(account -> account.isEmpty()
                        ? CompletableFuture.completedFuture(Optional.<AtriumUser>empty())
                        : ctx.users().load(account.get().uuid()))
                .whenComplete((loaded, error) -> {
                    if (error != null) {
                        ctx.logger().error("/back: loading {} failed", rawName, error);
                        reply(sender, "core.error");
                        return;
                    }
                    if (loaded.isEmpty()) {
                        reply(sender, "core.user-not-found", Msg.unparsed("name", rawName));
                        return;
                    }
                    AtriumUser user = loaded.get();
                    Player online = Bukkit.getPlayer(user.uuid());
                    try {
                        action.accept(new BackTarget(user.uuid(), user.name(), user, online, false));
                    } catch (RuntimeException e) {
                        ctx.logger().error("/back for {} failed", user.name(), e);
                        reply(sender, "core.error");
                    }
                });
    }

    private BackTarget selfTarget(CommandSourceStack source, Player player) throws CommandSyntaxException {
        Optional<AtriumUser> user = ctx.users().cached(player.getUniqueId());
        if (user.isEmpty()) {
            throw cmds.fail(source, "core.error");
        }
        return new BackTarget(player.getUniqueId(), player.getName(), user.get(), player, true);
    }

    // ---- helpers ----

    /** Sends on the recipient's own thread, so an async user lookup never touches a player off-thread. */
    private void reply(CommandSender recipient, String key, TagResolver... resolvers) {
        if (recipient instanceof Player player) {
            ctx.scheduler().entity(player, () -> messages.send(player, key, resolvers), null);
        } else {
            messages.send(recipient, key, resolvers);
        }
    }

    /** The player's current location, read on the player's own thread. */
    private CompletableFuture<Location> locationOf(Player player) {
        return ctx.scheduler().supplyOnEntity(player, player::getLocation);
    }
}
