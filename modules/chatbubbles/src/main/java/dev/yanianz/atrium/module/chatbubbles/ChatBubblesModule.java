package dev.yanianz.atrium.module.chatbubbles;

import dev.yanianz.atrium.api.moderation.MuteService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.api.vanish.VanishStateChangeEvent;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Shows a short, floating {@link TextDisplay} above a player's head after their chat message.
 *
 * <p>The display is NOT mounted as a passenger: Paper refuses cross-world teleports of a player who carries
 * passengers ({@code CraftPlayer#teleport0} returns false), so a bubble would block /home, /warp, /spawn.
 * Instead a short-lived timer on the player's thread moves the display with interpolation while both are
 * owned by the same region; otherwise the bubble is removed. It is created on the player's own region
 * thread after the chat event completed and was not cancelled. Player text is inserted literally; it is
 * never parsed as MiniMessage. The text is the final {@code message()} so chat filters also apply here.
 */
public final class ChatBubblesModule implements AtriumModule, Listener {
    static final String PERM_USE = "atrium.chatbubbles";

    /** Ticks between bubble moves; also the client-side interpolation duration. */
    private static final long FOLLOW_PERIOD = 2L;

    private ModuleContext ctx;
    private ConfigHandle<ChatBubblesConfig> config;
    private final Map<UUID, Bubble> bubbles = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "chatbubbles";
    }

    @Override
    public String description() {
        return "Floating TextDisplay chat bubbles above players";
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.config = ctx.config(ChatBubblesConfig.class, ChatBubblesConfig::new);
        ctx.listen(this);
        ctx.permission(PERM_USE, PermissionDefault.TRUE, "Show chat bubbles for your messages");
    }

    @Override
    public void disable() {
        for (Map.Entry<UUID, Bubble> entry : bubbles.entrySet()) {
            Bubble bubble = entry.getValue();
            bubble.task.cancel();
            removeLater(bubble.display);
        }
        bubbles.clear();
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
            if (event.isCancelled() || !cfg.enabled || !player.isOnline() || player.isDead()) {
                return;
            }
            if (!player.hasPermission(PERM_USE) || unavailable(player.getUniqueId())) {
                return;
            }
            show(player, text);
        }, null);
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

    // ---- display lifecycle (always on the player's region thread) ----

    private void show(Player player, String text) {
        ChatBubblesConfig cfg = config.get();
        List<String> lines = BubbleText.lines(text, cfg.maxLength, cfg.charsPerLine, cfg.maxLines);
        if (lines.isEmpty()) {
            return;
        }
        removeNow(player.getUniqueId());
        Component component = Component.text(String.join("\n", lines)).color(textColor(cfg.textColor));
        TextDisplay display;
        try {
            display = player.getWorld().spawn(anchor(player, cfg), TextDisplay.class,
                    entity -> configure(entity, component, cfg));
        } catch (RuntimeException error) {
            ctx.logger().warn("Could not create a chat bubble for {}", player.getName(), error);
            return;
        }
        if (cfg.hideFromSender) {
            player.hideEntity(ctx.plugin(), display);
        }
        Bubble bubble = new Bubble(display);
        Bubble previous = bubbles.put(player.getUniqueId(), bubble);
        if (previous != null) {
            previous.task.cancel();
            removeLater(previous.display);
        }
        long ticks = 20L * Math.clamp(cfg.durationSeconds, 1, 60);
        long deadline = player.getWorld().getGameTime() + ticks;
        try {
            // Runs on the player's thread; removes the bubble when it expires or leaves the player's region.
            bubble.task = ctx.track(ctx.scheduler().entityTimer(player,
                    () -> follow(player, bubble, deadline),
                    () -> removeLater(display), FOLLOW_PERIOD, FOLLOW_PERIOD));
        } catch (RuntimeException error) {
            ctx.logger().warn("Could not schedule the expiry of a chat bubble for {}", player.getName(), error);
            removeNow(player.getUniqueId());
        }
    }

    private void configure(TextDisplay display, Component text, ChatBubblesConfig cfg) {
        display.setPersistent(false);
        display.setVisibleByDefault(true);
        display.text(text);
        display.setLineWidth(Math.clamp(cfg.lineWidth, 1, 1000));
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.setBillboard(Display.Billboard.CENTER);
        display.setSeeThrough(cfg.seeThrough);
        display.setShadowed(cfg.shadowed);
        display.setDefaultBackground(false);
        display.setBackgroundColor(background(cfg.backgroundColor));
        display.setViewRange(Math.clamp(cfg.viewRange, 1f, 128f));
        display.setTeleportDuration((int) FOLLOW_PERIOD);
    }

    private static Location anchor(Player player, ChatBubblesConfig cfg) {
        Location location = player.getLocation().add(0, cfg.yOffset, 0);
        location.setYaw(0f);
        location.setPitch(0f);
        return location;
    }

    /** Player thread. */
    private void follow(Player player, Bubble bubble, long deadline) {
        TextDisplay display = bubble.display;
        boolean current = bubbles.get(player.getUniqueId()) == bubble;
        if (!current || !player.isOnline() || !display.isValid()
                || player.getWorld().getGameTime() >= deadline
                || display.getWorld() != player.getWorld()
                || !Bukkit.isOwnedByCurrentRegion(display)) {
            bubble.task.cancel();
            bubbles.remove(player.getUniqueId(), bubble);
            if (display.isValid() && Bukkit.isOwnedByCurrentRegion(display)) {
                display.remove();
            } else {
                removeLater(display);
            }
            return;
        }
        Location target = anchor(player, config.get());
        if (display.getLocation().distanceSquared(target) > 0.0025) {
            display.teleportAsync(target).exceptionally(error -> {
                ctx.logger().warn("Could not move the chat bubble of {}", player.getName(), error);
                return false;
            });
        }
    }

    /** Removes the bubble of a player on that player's thread. */
    private void removeNow(UUID owner) {
        Bubble bubble = bubbles.remove(owner);
        if (bubble == null) {
            return;
        }
        bubble.task.cancel();
        if (bubble.display.isValid() && Bukkit.isOwnedByCurrentRegion(bubble.display)) {
            bubble.display.remove();
        } else {
            removeLater(bubble.display);
        }
    }

    private void removeLater(TextDisplay display) {
        if (!ctx.plugin().isEnabled()) {
            // Server stop: displays are non-persistent and must not be scheduled after disable.
            return;
        }
        ctx.scheduler().entity(display, () -> {
            if (display.isValid()) {
                display.remove();
            }
        }, null);
    }

    // ---- cleanup events ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        removeNow(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        removeNow(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        removeNow(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        removeNow(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanish(VanishStateChangeEvent event) {
        if (event.isVanishing()) {
            removeNow(event.getPlayer().getUniqueId());
        }
    }

    // ---- helpers ----

    private static TextColor textColor(String raw) {
        if (raw == null || raw.isBlank()) {
            return NamedTextColor.WHITE;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("#")) {
            TextColor hex = TextColor.fromHexString(value);
            return hex == null ? NamedTextColor.WHITE : hex;
        }
        NamedTextColor named = NamedTextColor.NAMES.value(value);
        return named == null ? NamedTextColor.WHITE : named;
    }

    private Color background(String raw) {
        if (raw == null || raw.isBlank()) {
            return Color.fromARGB(0x40000000);
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        try {
            return Color.fromARGB((int) Long.parseLong(hex, 16));
        } catch (NumberFormatException error) {
            ctx.logger().warn("Invalid backgroundColor '{}' in modules/chatbubbles.yml; using #40000000", raw, error);
            return Color.fromARGB(0x40000000);
        }
    }

    private static final class Bubble {
        private final TextDisplay display;
        private volatile TaskHandle task = TaskHandle.NOOP;

        private Bubble(TextDisplay display) {
            this.display = display;
        }
    }
}
