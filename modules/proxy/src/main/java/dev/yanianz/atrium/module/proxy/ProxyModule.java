package dev.yanianz.atrium.module.proxy;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.yanianz.atrium.api.proxy.NetworkPlayer;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.config.ConfigService;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Proxy transport and network commands: {@code /server}, {@code /glist} and {@code /proxy status}.
 *
 * <p>Only the standard {@code BungeeCord} plugin channel is used, so BungeeCord and Velocity both work
 * without an extra plugin on the proxy.
 */
public final class ProxyModule implements AtriumModule, Listener {
    static final String PERM_SERVER = "atrium.server";
    static final String PERM_GLIST = "atrium.glist";
    static final String PERM_ADMIN = "atrium.proxy.admin";

    private static final SecureRandom RANDOM = new SecureRandom();

    private ModuleContext ctx;
    private Cmds cmds;
    private ConfigHandle<ProxyConfig> config;
    private ProxyNode node;

    @Override
    public String id() {
        return "proxy";
    }

    @Override
    public String description() {
        return "Cross-server transport, /server and /glist";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.cmds = ctx.commands();
        this.config = ctx.config(ProxyConfig.class, ProxyConfig::new);
        byte[] secret = loadSecret();
        this.node = new ProxyNode(ctx, secret, config.get());
        node.enable();
        ctx.service(ProxyService.class, node);
        ctx.listen(this);

        ctx.permission(PERM_SERVER, PermissionDefault.TRUE, "Use /server to switch servers");
        ctx.permission(PERM_GLIST, PermissionDefault.TRUE, "List players across the network");
        ctx.permission(PERM_ADMIN, PermissionDefault.OP, "View proxy status (/proxy status)");

        registerCommands();

        long interval = Math.max(2, Math.min(300, config.get().syncIntervalSeconds)) * 20L;
        ctx.track(ctx.scheduler().globalTimer(node::sync, 20L * 5, interval));
        ctx.scheduler().globalLater(node::sync, 20L);
    }

    @Override
    public void reload() {
        ctx.logger().info("Proxy channel registration and the shared secret are only applied on the next start");
    }

    @Override
    public void disable() {
        if (node != null) {
            node.disable();
        }
    }

    // ---- secret ----

    /**
     * Reads the shared secret from the config, generating and persisting a random 32-byte value on first
     * start. The value is never logged: the admin copies it to the other servers by opening the file.
     */
    private byte[] loadSecret() throws Exception {
        ProxyConfig current = config.get();
        String value = current.sharedSecret == null ? "" : current.sharedSecret.strip();
        if (value.isEmpty()) {
            byte[] random = new byte[32];
            RANDOM.nextBytes(random);
            String generated = Base64.getEncoder().encodeToString(random);
            persistSecret(generated);
            config.reload();
            value = config.get().sharedSecret == null ? "" : config.get().sharedSecret.strip();
            ctx.logger().info("Generated a new proxy shared secret. Copy it from modules/proxy.yml to every server; "
                    + "it is deliberately not printed here.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("proxy.yml sharedSecret is not valid Base64", e);
        }
        if (decoded.length < 16) {
            throw new IllegalStateException("proxy.yml sharedSecret must decode to at least 16 bytes");
        }
        return decoded;
    }

    private void persistSecret(String secret) throws Exception {
        var loader = ConfigService.loader(config.path());
        var root = loader.load();
        root.node("shared-secret").set(secret);
        loader.save(root);
    }

    // ---- commands ----

    private void registerCommands() {
        ctx.command(CommandSpec.of(Cmds.literal("server", PERM_SERVER)
                .executes(this::listServers)
                .then(Commands.argument("server", StringArgumentType.word())
                        .suggests(Cmds.suggest(() -> node.servers()))
                        .executes(this::connect))
                .build(), "Move to another server on the network"));

        ctx.command(CommandSpec.of(Cmds.literal("glist", PERM_GLIST)
                .executes(this::glist)
                .build(), "List players across the network"));

        ctx.command(CommandSpec.of(Cmds.literal("proxy", PERM_ADMIN)
                .executes(this::statusUsage)
                .then(Commands.literal("status").executes(this::status))
                .build(), "Proxy network status"));
    }

    private int statusUsage(CommandContext<CommandSourceStack> c) {
        ctx.messages().send(c.getSource().getSender(), "proxy.status-usage");
        return Cmds.OK;
    }

    private int listServers(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Collection<String> names = node.servers();
        if (names.isEmpty()) {
            ctx.messages().send(sender, "proxy.server-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "proxy.server-list-header", Msg.unparsed("count",
                Integer.toString(names.size())));
        for (String name : names) {
            ctx.messages().send(sender, "proxy.server-list-entry", Msg.unparsed("server", name));
        }
        return Cmds.OK;
    }

    private int connect(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Player player = cmds.player(c.getSource());
        String server = StringArgumentType.getString(c, "server");
        if (!ProxyNode.validServerName(server)) {
            throw cmds.fail(c.getSource(), "proxy.invalid-server", Msg.unparsed("server", server));
        }
        String permission = PERM_SERVER + "." + server;
        if (!player.hasPermission(permission)) {
            throw cmds.fail(c.getSource(), "proxy.no-server-permission",
                    Msg.unparsed("permission", permission));
        }
        if (server.equalsIgnoreCase(node.serverName())) {
            ctx.messages().send(player, "proxy.already-here", Msg.unparsed("server", server));
            return Cmds.OK;
        }
        if (!node.connect(player, server)) {
            throw cmds.fail(c.getSource(), "proxy.invalid-server", Msg.unparsed("server", server));
        }
        ctx.messages().send(player, "proxy.connecting", Msg.unparsed("server", server));
        return Cmds.OK;
    }

    private int glist(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        Player viewer = sender instanceof Player player ? player : null;

        Map<String, TreeSet<String>> byServer = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (NetworkPlayer entry : node.networkPlayers()) {
            byServer.computeIfAbsent(entry.server(), k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER))
                    .add(entry.name());
        }
        String own = node.serverName();
        TreeSet<String> local = byServer.computeIfAbsent(own, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER));
        for (Player player : Bukkit.getOnlinePlayers()) {
            local.add(player.getName());
        }
        if (viewer != null) {
            Optional<VanishService> vanish = ctx.services().get(VanishService.class);
            if (vanish.isPresent()) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (!vanish.get().canSee(viewer, player)) {
                        local.remove(player.getName());
                    }
                }
            }
        }

        int total = byServer.values().stream().mapToInt(Set::size).sum();
        if (total == 0) {
            ctx.messages().send(sender, "proxy.glist-empty");
            return Cmds.OK;
        }
        ctx.messages().send(sender, "proxy.glist-header", Msg.unparsed("players", Integer.toString(total)),
                Msg.unparsed("servers", Integer.toString(byServer.size())));
        for (Map.Entry<String, TreeSet<String>> entry : byServer.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            ctx.messages().send(sender, "proxy.glist-server", Msg.unparsed("server", entry.getKey()),
                    Msg.unparsed("count", Integer.toString(entry.getValue().size())),
                    Msg.unparsed("players", String.join(", ", entry.getValue())));
        }
        return Cmds.OK;
    }

    private int status(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ProxyNode.Status status = node.status();
        ctx.messages().send(sender, "proxy.status-header");
        ctx.messages().send(sender, "proxy.status-server", tag("server", status.server()));
        ctx.messages().send(sender, "proxy.status-sync", tag("sync", status.lastSyncText()));
        ctx.messages().send(sender, "proxy.status-queue", tag("queued", Integer.toString(status.queued())),
                tag("dropped", Long.toString(status.dropped())));
        ctx.messages().send(sender, "proxy.status-rejected", tag("rejected", Long.toString(status.rejected())));
        ctx.messages().send(sender, "proxy.status-cache", tag("servers", Integer.toString(status.servers())),
                tag("players", Integer.toString(status.players())));
        return Cmds.OK;
    }

    private static TagResolver tag(String name, String value) {
        return Msg.unparsed(name, value);
    }

    // ---- player tracking ----

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        node.playerJoined(player);
        node.flushQueue(player);
        ctx.scheduler().entityLater(player, node::sync, null, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        node.playerQuit(event.getPlayer());
    }

}
