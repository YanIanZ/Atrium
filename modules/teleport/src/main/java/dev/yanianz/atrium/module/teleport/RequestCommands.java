package dev.yanianz.atrium.module.teleport;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.chat.IgnoreService;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import dev.yanianz.atrium.module.teleport.RequestBook.Request;
import dev.yanianz.atrium.module.teleport.RequestBook.Type;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** /tpa, /tpahere, /tpaall, /tpaccept, /tpdeny, /tpacancel, /tptoggle. */
final class RequestCommands implements Listener {
    static final String PERM_TPA = "atrium.tpa";
    static final String PERM_TPAHERE = "atrium.tpahere";
    static final String PERM_TPAALL = "atrium.tpaall";
    static final String PERM_TPACCEPT = "atrium.tpaccept";
    static final String PERM_TPDENY = "atrium.tpdeny";
    static final String PERM_TPACANCEL = "atrium.tpacancel";
    static final String PERM_TPTOGGLE = "atrium.tptoggle";
    /** Numeric: atrium.tpa.max.<n> raises the /tpa reach. */
    static final String PERM_TPA_MAX = "atrium.tpa.max";

    private final ModuleContext ctx;
    private final Cmds cmds;
    private final Messages messages;
    private final TeleportServiceImpl service;
    private final ConfigHandle<TeleportConfig> config;
    private final RequestBook book;

    RequestCommands(ModuleContext ctx, TeleportServiceImpl service, ConfigHandle<TeleportConfig> config) {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.messages = ctx.messages();
        this.service = service;
        this.config = config;
        this.book = new RequestBook(Clock.systemUTC(),
                () -> Duration.ofSeconds(Math.max(5, config.get().requests.expireSeconds)));
    }

    void register() {
        ctx.command(CommandSpec.of(Cmds.literal("tpa", PERM_TPA)
                .executes(c -> usage(c, "teleport.usage-tpa"))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(c -> request(c, Type.TPA)))
                .build(), "Ask to teleport to a player", "call"));
        ctx.command(CommandSpec.of(Cmds.literal("tpahere", PERM_TPAHERE)
                .executes(c -> usage(c, "teleport.usage-tpahere"))
                .then(Commands.argument("target", ArgumentTypes.player()).executes(c -> request(c, Type.TPAHERE)))
                .build(), "Ask a player to teleport to you"));
        ctx.command(CommandSpec.of(Cmds.literal("tpaall", PERM_TPAALL)
                .executes(this::tpaAll)
                .build(), "Ask every online player to teleport to you"));
        ctx.command(CommandSpec.of(Cmds.literal("tpaccept", PERM_TPACCEPT)
                .executes(this::accept)
                .then(Commands.argument("player", ArgumentTypes.player()).executes(this::accept))
                .build(), "Accept a teleport request", "tpyes"));
        ctx.command(CommandSpec.of(Cmds.literal("tpdeny", PERM_TPDENY)
                .executes(this::deny)
                .then(Commands.argument("player", ArgumentTypes.player()).executes(this::deny))
                .build(), "Deny a teleport request", "tpno"));
        ctx.command(CommandSpec.of(Cmds.literal("tpacancel", PERM_TPACANCEL)
                .executes(this::cancel)
                .then(Commands.argument("player", ArgumentTypes.player()).executes(this::cancel))
                .build(), "Cancel your teleport request"));
        ctx.command(CommandSpec.of(Cmds.literal("tptoggle", PERM_TPTOGGLE)
                .executes(this::toggle)
                .build(), "Allow or refuse teleport requests"));
    }

    /** A bare root command explains itself instead of failing silently. */
    private int usage(CommandContext<CommandSourceStack> c, String key) {
        messages.send(c.getSource().getSender(), key);
        return Cmds.OK;
    }

    /** Expires requests and tells both sides. Runs every second off the main threads. */
    void expireTick() {
        for (Request request : book.expire()) {
            Player sender = Bukkit.getPlayer(request.sender());
            Player receiver = Bukkit.getPlayer(request.receiver());
            if (sender != null) {
                messages.send(sender, "teleport.request-expired-sender",
                        Msg.unparsed("player", receiver == null ? "?" : receiver.getName()));
            }
            if (receiver != null) {
                messages.send(receiver, "teleport.request-expired-receiver",
                        Msg.unparsed("player", sender == null ? "?" : sender.getName()));
            }
        }
    }

    private int request(CommandContext<CommandSourceStack> c, Type type) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Player target = cmds.argPlayer(c, "target");
        if (target.equals(self)) {
            throw cmds.fail(c.getSource(), "teleport.cannot-self");
        }
        boolean accepts = service.state(target).map(TeleportState::acceptsRequests).orElse(true);
        if (!accepts && !self.hasPermission(TeleportCommands.PERM_TOGGLE_BYPASS)) {
            throw cmds.fail(c.getSource(), "teleport.target-toggled", Msg.unparsed("player", target.getName()));
        }
        TeleportConfig.Requests cfg = config.get().requests;
        if (!cfg.allowCrossWorld && !self.getWorld().equals(target.getWorld())) {
            throw cmds.fail(c.getSource(), "teleport.request-other-world");
        }
        Optional<IgnoreService> ignored = ctx.services().get(IgnoreService.class);
        if (ignored.isPresent() && ignored.get().isIgnoring(target.getUniqueId(), self.getUniqueId())) {
            throw cmds.fail(c.getSource(), "teleport.request-ignored", Msg.unparsed("player", target.getName()));
        }
        if (ignored.isPresent() && ignored.get().isIgnoring(self.getUniqueId(), target.getUniqueId())) {
            throw cmds.fail(c.getSource(), "teleport.request-you-ignore", Msg.unparsed("player", target.getName()));
        }
        String held = self.getInventory().getItemInMainHand().getType().name();
        if (RequestRules.blockedItem(cfg.blockedMaterials, held)) {
            throw cmds.fail(c.getSource(), "teleport.request-blocked-item", Msg.unparsed("item", held));
        }
        if (book.hasPending(self.getUniqueId(), target.getUniqueId(), type)) {
            throw cmds.fail(c.getSource(), "teleport.request-already", Msg.unparsed("player", target.getName()));
        }
        int configured = Math.max(0, cfg.maxDistance);
        int limit = configured <= 0 ? 0 : Permissions.limit(self, PERM_TPA_MAX, configured);
        if (limit <= 0 || !self.getWorld().equals(target.getWorld())) {
            sendRequest(self, target, type);
            return Cmds.OK;
        }
        // Both locations are read on their own threads, then the reach is checked before anything is stored.
        ctx.scheduler().supplyOnEntity(self, self::getLocation)
                .thenCombine(ctx.scheduler().supplyOnEntity(target, target::getLocation),
                        (from, to) -> RequestRules.withinDistance(from.distance(to), limit))
                .whenComplete((allowed, error) -> {
                    if (error != null) {
                        ctx.logger().warn("Could not read the locations of {} and {} for /tpa", self.getName(),
                                target.getName(), error);
                        return;
                    }
                    if (!Boolean.TRUE.equals(allowed)) {
                        messages.send(self, "teleport.request-too-far", Msg.number("distance", limit),
                                Msg.unparsed("player", target.getName()));
                        return;
                    }
                    sendRequest(self, target, type);
                });
        return Cmds.OK;
    }

    /** Stores the request and tells both sides. Also used by /tpaall. */
    private void sendRequest(Player self, Player target, Type type) {
        Request request = book.add(self.getUniqueId(), target.getUniqueId(), type);
        long seconds = Duration.between(request.created(), request.expires()).toSeconds();
        messages.send(self, "teleport.request-sent", Msg.unparsed("player", target.getName()), Msg.number("seconds", seconds));
        messages.send(target, type == Type.TPA ? "teleport.tpa-received" : "teleport.tpahere-received",
                Msg.unparsed("player", self.getName()), Msg.number("seconds", seconds));
        target.sendMessage(buttons(target, self.getName()));
    }

    /** /tpaall: asks every online player who accepts requests to come to the sender. */
    private int tpaAll(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        TeleportConfig.Requests cfg = config.get().requests;
        Optional<IgnoreService> ignored = ctx.services().get(IgnoreService.class);
        int sent = 0;
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.equals(self)) {
                continue;
            }
            if (!cfg.allowCrossWorld && !self.getWorld().equals(other.getWorld())) {
                continue;
            }
            if (!service.state(other).map(TeleportState::acceptsRequests).orElse(true)) {
                continue;
            }
            if (ignored.isPresent() && ignored.get().isIgnoring(other.getUniqueId(), self.getUniqueId())) {
                continue;
            }
            sendRequest(self, other, Type.TPAHERE);
            sent++;
        }
        messages.send(self, "teleport.tpaall", Msg.number("count", sent));
        return Cmds.OK;
    }

    /** Clickable accept / deny buttons. Player names are [A-Za-z0-9_], so they are safe in a command. */
    private Component buttons(Player viewer, String requester) {
        Component accept = messages.render(viewer, "teleport.button-accept")
                .clickEvent(ClickEvent.runCommand("/tpaccept " + requester))
                .hoverEvent(HoverEvent.showText(messages.render(viewer, "teleport.button-accept-hover")));
        Component deny = messages.render(viewer, "teleport.button-deny")
                .clickEvent(ClickEvent.runCommand("/tpdeny " + requester))
                .hoverEvent(HoverEvent.showText(messages.render(viewer, "teleport.button-deny-hover")));
        return messages.render(viewer, "teleport.request-buttons", Msg.component("accept", accept), Msg.component("deny", deny));
    }

    private Optional<UUID> namedPlayer(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return Cmds.hasArgument(c, "player") ? Optional.of(cmds.argPlayer(c, "player").getUniqueId()) : Optional.empty();
    }

    private int accept(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Optional<UUID> from = namedPlayer(c);
        // Only the named sender's request is taken; never someone else's as a fallback.
        Request request = book.take(self.getUniqueId(), from)
                .orElseThrow(() -> cmds.fail(c.getSource(), from.isPresent() ? "teleport.no-request-from" : "teleport.no-request",
                        Msg.unparsed("player", from.map(Bukkit::getPlayer).map(Player::getName).orElse("?"))));
        Player requester = Bukkit.getPlayer(request.sender());
        if (requester == null) {
            throw cmds.fail(c.getSource(), "teleport.requester-offline");
        }
        messages.send(self, "teleport.request-accepted-receiver", Msg.unparsed("player", requester.getName()));
        messages.send(requester, "teleport.request-accepted-sender", Msg.unparsed("player", self.getName()));
        if (request.type() == Type.TPA) {
            // The destination is where the accepting player stands now (this is their thread).
            Location destination = self.getLocation();
            service.teleport(requester, destination, TeleportCause.REQUEST, TeleportOptions.DEFAULT)
                    .thenAccept(result -> arrived(result, requester, self));
        } else {
            ctx.scheduler().supplyOnEntity(requester, requester::getLocation)
                    .thenCompose(destination -> service.teleport(self, destination, TeleportCause.REQUEST, TeleportOptions.DEFAULT))
                    .whenComplete((result, error) -> {
                        if (error != null) {
                            messages.send(self, "teleport.requester-offline");
                            return;
                        }
                        arrived(result, self, requester);
                    });
        }
        return Cmds.OK;
    }

    private void arrived(TeleportResult result, Player traveller, Player destination) {
        if (result == TeleportResult.SUCCESS) {
            messages.send(traveller, "teleport.tp-to-player", Msg.unparsed("player", destination.getName()));
        }
    }

    private int deny(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Optional<UUID> from = namedPlayer(c);
        Request request = book.take(self.getUniqueId(), from)
                .orElseThrow(() -> cmds.fail(c.getSource(), from.isPresent() ? "teleport.no-request-from" : "teleport.no-request",
                        Msg.unparsed("player", from.map(Bukkit::getPlayer).map(Player::getName).orElse("?"))));
        Player requester = Bukkit.getPlayer(request.sender());
        String name = requester == null ? "?" : requester.getName();
        messages.send(self, "teleport.request-denied-receiver", Msg.unparsed("player", name));
        if (requester != null) {
            messages.send(requester, "teleport.request-denied-sender", Msg.unparsed("player", self.getName()));
        }
        return Cmds.OK;
    }

    private int cancel(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        Optional<UUID> to = namedPlayer(c);
        Request request = book.cancel(self.getUniqueId(), to)
                .orElseThrow(() -> cmds.fail(c.getSource(), "teleport.no-outgoing"));
        Player receiver = Bukkit.getPlayer(request.receiver());
        String name = receiver == null ? "?" : receiver.getName();
        messages.send(self, "teleport.request-cancelled-sender", Msg.unparsed("player", name));
        if (receiver != null) {
            messages.send(receiver, "teleport.request-cancelled-receiver", Msg.unparsed("player", self.getName()));
        }
        return Cmds.OK;
    }

    private int toggle(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player self = cmds.player(c.getSource());
        TeleportState updated;
        try {
            updated = ctx.users().user(self).update(service.store().component, s -> s.withAcceptsRequests(!s.acceptsRequests()));
        } catch (IllegalStateException e) {
            ctx.logger().error("/tptoggle: teleport data of {} is not loaded", self.getName(), e);
            throw cmds.fail(c.getSource(), "core.error");
        }
        // Takes effect at once; confirmed only once it is stored.
        boolean accepts = updated.acceptsRequests();
        ctx.users().saveNow(self.getUniqueId()).whenComplete((v, error) -> {
            if (error != null) {
                ctx.logger().error("/tptoggle: saving the setting of {} failed", self.getName(), error);
                messages.send(self, "teleport.save-failed");
                return;
            }
            messages.send(self, accepts ? "teleport.tptoggle-on" : "teleport.tptoggle-off");
        });
        return Cmds.OK;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player quitting = event.getPlayer();
        for (Request request : book.removeAll(quitting.getUniqueId())) {
            UUID other = request.sender().equals(quitting.getUniqueId()) ? request.receiver() : request.sender();
            Player online = Bukkit.getPlayer(other);
            if (online != null) {
                messages.send(online, "teleport.request-cancelled-quit", Msg.unparsed("player", quitting.getName()));
            }
        }
    }
}
