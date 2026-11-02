package dev.yanianz.atrium.module.chatbubbles;

import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

/**
 * Server-side fallback: a short-lived {@link TextDisplay} that is moved on the player's thread every tick.
 * The display is NOT a passenger, because Paper refuses a cross-world teleport of a player who carries one
 * ({@code CraftPlayer#teleport0} returns false), which would block /home, /warp and /spawn.
 *
 * <p>Moving every tick still leaves the client one tick behind while walking, so the target includes the
 * player's velocity (see {@link BubbleAnchor}). The bubble ends when it expires, when the player leaves its
 * region, changes world or dies.
 */
final class FollowBubbleRenderer implements BubbleRenderer {
    /** One move per tick; also the client-side interpolation duration. */
    private static final long PERIOD = 1L;
    private static final double MIN_MOVE_SQUARED = 0.0004;

    private final ModuleContext ctx;
    private final Supplier<ChatBubblesConfig> config;
    private final Map<UUID, Bubble> bubbles = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    FollowBubbleRenderer(ModuleContext ctx, Supplier<ChatBubblesConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    @Override
    public void show(Player player, Component text, ChatBubblesConfig cfg) {
        if (!running) {
            return;
        }
        remove(player.getUniqueId());
        TextDisplay display;
        try {
            display = player.getWorld().spawn(anchor(player, cfg), TextDisplay.class,
                    entity -> configure(entity, text, cfg));
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
                    () -> removeLater(display), PERIOD, PERIOD));
        } catch (RuntimeException error) {
            ctx.logger().warn("Could not schedule the expiry of a chat bubble for {}", player.getName(), error);
            remove(player.getUniqueId());
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
        display.setBackgroundColor(Color.fromARGB(BubbleColors.backgroundArgb(cfg.backgroundColor)));
        // Bukkit expects a multiplier of the default 64-block view range, not block counts.
        display.setViewRange((float) Math.clamp(cfg.viewRange / 64d, 0.1d, 4d));
        display.setTeleportDuration((int) PERIOD);
    }

    private static Location anchor(Player player, ChatBubblesConfig cfg) {
        Location base = player.getLocation();
        BubbleAnchor.Point point = BubbleAnchor.follow(
                base.getX(), base.getY(), base.getZ(),
                player.getVelocity().getX(), player.getVelocity().getY(), player.getVelocity().getZ(),
                player.getEyeHeight(), cfg.effectiveHeightAboveEyes());
        Location location = new Location(player.getWorld(), point.x(), point.y(), point.z());
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
            discard(display);
            return;
        }
        Location target = anchor(player, config.get());
        if (display.getLocation().distanceSquared(target) > MIN_MOVE_SQUARED) {
            display.teleportAsync(target).exceptionally(error -> {
                ctx.logger().warn("Could not move the chat bubble of {}", player.getName(), error);
                return false;
            });
        }
    }

    @Override
    public void remove(UUID speaker) {
        Bubble bubble = bubbles.remove(speaker);
        if (bubble == null) {
            return;
        }
        bubble.task.cancel();
        discard(bubble.display);
    }

    @Override
    public void shutdown() {
        running = false;
        for (Bubble bubble : bubbles.values()) {
            bubble.task.cancel();
            removeLater(bubble.display);
        }
        bubbles.clear();
    }

    /** Removes now when the current thread owns the display, otherwise on the display's own thread. */
    private void discard(TextDisplay display) {
        if (display.isValid() && Bukkit.isOwnedByCurrentRegion(display)) {
            display.remove();
            return;
        }
        removeLater(display);
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

    private static final class Bubble {
        private final TextDisplay display;
        private volatile TaskHandle task = TaskHandle.NOOP;

        private Bubble(TextDisplay display) {
            this.display = display;
        }
    }
}
