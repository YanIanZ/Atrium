package dev.yanianz.atrium.module.chatbubbles;

import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.api.vanish.VanishStateChangeEvent;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Shows a short, floating text after a player's chat message.
 *
 * <p>With the packetevents plugin a client-side TextDisplay is mounted on the speaker as a fake passenger:
 * the client moves it with the player, so there is no visible lag, no server entity and no teleport
 * problem. Without PacketEvents a server TextDisplay follows every tick with velocity prediction. Neither
 * display is a real passenger, because Paper refuses a cross-world teleport of a player who carries one
 * ({@code CraftPlayer#teleport0} returns false), which would block /home, /warp and /spawn.
 *
 * <p>Player text is inserted literally; it is never parsed as MiniMessage. The text is the final
 * {@code message()} so chat filters also apply here.
 */
public final class ChatBubblesModule implements AtriumModule, Listener {
    static final String PERM_USE = "atrium.chatbubbles";
    static final String PERM_ADMIN = "atrium.chatbubbles.admin";

    private ModuleContext ctx;
    private ConfigHandle<ChatBubblesConfig> config;
    private volatile FollowBubbleRenderer follow;
    private volatile PacketBubbleRenderer packets;
    private volatile BubbleRenderer active;
    private BubbleMode configuredMode = BubbleMode.AUTO;
    private BubbleMode effectiveMode = BubbleMode.FOLLOW;
    private boolean packetEventsPresent;

    @Override
    public String id() {
        return "chatbubbles";
    }

    @Override
    public String description() {
        return "Chat bubbles above players, client-side or server-side";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(ChatBubblesConfig.class, ChatBubblesConfig::new);
        ctx.listen(this);
        ctx.permission(PERM_USE, PermissionDefault.TRUE, "Show chat bubbles for your messages");
        ctx.permission(PERM_ADMIN, PermissionDefault.OP, "Inspect the chat bubble rendering mode");
        registerCommands();
        setupRenderers();
    }

    @Override
    public void reload() {
        teardownRenderers();
        setupRenderers();
    }

    @Override
    public void disable() {
        teardownRenderers();
    }

    // ---- renderer selection ----

    private void setupRenderers() {
        ChatBubblesConfig cfg = config.get();
        List<String> problems = new ArrayList<>();
        configuredMode = BubbleMode.parse(cfg.mode, (option, problem) -> problems.add(option + ": " + problem));
        for (String problem : problems) {
            ctx.logger().warn("modules/chatbubbles.yml {}", problem);
        }
        if (!BubbleColors.validBackground(cfg.backgroundColor)) {
            ctx.logger().warn("Invalid backgroundColor '{}' in modules/chatbubbles.yml; using #40000000",
                    cfg.backgroundColor);
        }
        packetEventsPresent = Bukkit.getPluginManager().isPluginEnabled(PacketBubbleFactory.PLUGIN);
        follow = new FollowBubbleRenderer(ctx, config::get);
        packets = null;
        if (configuredMode != BubbleMode.FOLLOW && packetEventsPresent) {
            packets = PacketBubbleFactory.create(ctx, config::get);
        }
        boolean packetsReady = packets != null;
        effectiveMode = BubbleMode.effective(configuredMode, packetsReady);
        active = effectiveMode == BubbleMode.PACKETS ? packets : follow;
        if (configuredMode == BubbleMode.PACKETS && !packetsReady) {
            ctx.logger().warn("chatbubbles mode is 'packets' but PacketEvents is not usable; using follow mode");
        }
        ctx.logger().info("Chat bubble mode: {} (configured {}, PacketEvents {})",
                effectiveMode.id(), configuredMode.id(),
                packetsReady ? "usable" : packetEventsPresent ? "enabled but unusable" : "missing");
    }

    private void teardownRenderers() {
        PacketBubbleRenderer oldPackets = packets;
        FollowBubbleRenderer oldFollow = follow;
        packets = null;
        follow = null;
        active = null;
        if (oldPackets != null) {
            oldPackets.shutdown();
        }
        if (oldFollow != null) {
            oldFollow.shutdown();
        }
    }

    // ---- chat ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!config.get().enabled) {
            return;
        }
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (text.isBlank()) {
            return;
        }
        // The async chat thread must not touch entities; creation runs on the player's own thread.
        ctx.scheduler().entity(player, () -> {
            ChatBubblesConfig cfg = config.get();
            BubbleRenderer renderer = active;
            if (event.isCancelled() || !cfg.enabled || renderer == null || !player.isOnline() || player.isDead()) {
                return;
            }
            if (!player.hasPermission(PERM_USE) || unavailable(player.getUniqueId())) {
                return;
            }
            show(renderer, player, text, cfg);
        }, null);
    }

    private void show(BubbleRenderer renderer, Player player, String text, ChatBubblesConfig cfg) {
        List<String> lines = BubbleText.lines(text, cfg.maxLength, cfg.charsPerLine, cfg.maxLines);
        if (lines.isEmpty()) {
            return;
        }
        TextColor color = BubbleColors.text(cfg.textColor);
        Component component = Component.text(String.join("\n", lines)).color(color);
        renderer.show(player, component, cfg);
    }

    /** Vanished and shadow-muted players never get a visible bubble. */
    private boolean unavailable(UUID player) {
        if (ctx.services().get(VanishService.class)
                .map(service -> service.isVanished(player)).orElse(false)) {
            return true;
        }
        return ctx.services().get(MuteService.class).map(mutes ->
                mutes.isShadowMuted(player) || mutes.isMuted(player)).orElse(false);
    }

    // ---- packet viewer tracking (only meaningful in packets mode) ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        PacketBubbleRenderer renderer = packets;
        if (renderer != null) {
            renderer.track(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PacketBubbleRenderer renderer = packets;
        if (renderer != null) {
            renderer.track(event.getPlayer());
        }
    }

    // ---- cleanup events ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        endBubble(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        endBubble(player);
        PacketBubbleRenderer renderer = packets;
        if (renderer != null) {
            renderer.forget(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        endBubble(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        endBubble(event.getPlayer().getUniqueId());
        PacketBubbleRenderer renderer = packets;
        if (renderer != null) {
            renderer.track(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanish(VanishStateChangeEvent event) {
        if (event.isVanishing()) {
            endBubble(event.getPlayer().getUniqueId());
        }
    }

    /** Ends a bubble in whichever renderer holds it; both are idempotent. */
    private void endBubble(UUID speaker) {
        FollowBubbleRenderer followRenderer = follow;
        if (followRenderer != null) {
            followRenderer.remove(speaker);
        }
        PacketBubbleRenderer packetRenderer = packets;
        if (packetRenderer != null) {
            packetRenderer.remove(speaker);
        }
    }

    // ---- command ----

    private void registerCommands() {
        ctx.command(CommandSpec.of(Cmds.literal("chatbubbles", PERM_ADMIN)
                .executes(this::mode)
                .then(Cmds.literal("mode").executes(this::mode))
                .build(), "Show how chat bubbles are rendered", "chatbubbles"));
    }

    private int mode(CommandContext<CommandSourceStack> c) {
        PacketBubbleRenderer packetRenderer = packets;
        String statusKey;
        if (packetRenderer != null) {
            statusKey = "chatbubbles.packets-usable";
        } else if (!packetEventsPresent) {
            statusKey = "chatbubbles.packets-missing";
        } else if (configuredMode == BubbleMode.FOLLOW) {
            statusKey = "chatbubbles.packets-not-used";
        } else {
            statusKey = "chatbubbles.packets-unusable";
        }
        Component status = ctx.messages().render(c.getSource().getSender(), statusKey);
        ctx.messages().send(c.getSource().getSender(), "chatbubbles.mode",
                Msg.unparsed("mode", effectiveMode().id()),
                Msg.unparsed("configured", configuredMode.id()),
                Msg.component("packets", status));
        return Cmds.OK;
    }

    /** The renderer currently in use, for the admin command and tests. */
    BubbleMode effectiveMode() {
        return effectiveMode;
    }
}
