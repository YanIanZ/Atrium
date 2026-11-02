package dev.yanianz.atrium.module.signs;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Sends the scrolling lines of every dynamic sign to the players near it.
 *
 * <p>Only packets are sent ({@code Player#sendSignChange}); the block, its NBT and its real text are never
 * changed, so nothing is persisted in the world and a sign survives a restart as the ordinary sign it is. The
 * global timer only decides who to update; the lines are built and sent on each viewer's own thread, and no
 * block is ever read from another region.
 */
final class DynamicSigns implements Listener {
    private static final long MIN_PERIOD_TICKS = 5L;

    private final ModuleContext ctx;
    private final ConfigHandle<SignsConfig> config;
    private final DynamicSignStore store;
    private final Map<String, Long> lastFrame = new ConcurrentHashMap<>();
    private TaskHandle task = TaskHandle.NOOP;
    private volatile boolean stopping;

    DynamicSigns(ModuleContext ctx, ConfigHandle<SignsConfig> config, Path file) {
        this.ctx = ctx;
        this.config = config;
        this.store = new DynamicSignStore(file, ctx.logger());
    }

    DynamicSignStore store() {
        return store;
    }

    /** Reads the file (blocking) and starts the timer when the feature is on. */
    void start() throws java.io.IOException {
        store.load();
        restart();
    }

    void restart() {
        stopping = true;
        task.cancel();
        stopping = false;
        lastFrame.clear();
        if (!config.get().dynamicSignsEnabled || store.all().isEmpty()) {
            return;
        }
        long period = Math.max(MIN_PERIOD_TICKS, config.get().dynamicSignsIntervalTicks());
        task = ctx.track(ctx.scheduler().globalTimer(this::tick, period, period));
        ctx.logger().info("Loaded {} dynamic sign(s)", store.all().size());
    }

    void stop() {
        stopping = true;
        task.cancel();
        task = TaskHandle.NOOP;
        lastFrame.clear();
    }

    /** Called on the global thread: hand each nearby player to their own thread. */
    private void tick() {
        if (stopping || store.all().isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player, () -> update(player), null);
        }
    }

    /**
     * Runs on the viewer's thread: build and send the window of every sign in range.
     *
     * <p>{@code sendSignChange} with a colour and glow is the only Packet-level sign API and is marked deprecated;
     * the side-aware replacement edits a real block, which is exactly what a dynamic sign must not do.
     */
    @SuppressWarnings("deprecation")
    private void update(Player player) {
        if (stopping || !player.isOnline()) {
            return;
        }
        World world = player.getWorld();
        Location eye = player.getLocation();
        long now = System.currentTimeMillis();
        for (DynamicSignStore.DynamicSign sign : store.all()) {
            if (!sign.world().equals(world.getName())) {
                continue;
            }
            String key = player.getUniqueId() + "|" + sign.name();
            double dx = eye.getX() - (sign.x() + 0.5);
            double dy = eye.getY() - (sign.y() + 0.5);
            double dz = eye.getZ() - (sign.z() + 0.5);
            if (dx * dx + dy * dy + dz * dz > (double) sign.range() * sign.range()) {
                lastFrame.remove(key);
                continue;
            }
            int size = sign.lines().size();
            int offset = sign.personal() ? SignScroll.offsetFor(player.getUniqueId()) : 0;
            long frame = size > 4 ? SignScroll.frame(now, sign.intervalMillis(), offset) : 0;
            Long previous = lastFrame.get(key);
            // A short sign is re-sent every tick of the timer so a later line change shows up immediately.
            if (size > 4 && previous != null && previous == frame) {
                continue;
            }
            lastFrame.put(key, frame);
            List<Component> window = new ArrayList<>(4);
            for (String line : SignScroll.window(sign.lines(), frame)) {
                window.add(render(player, line));
            }
            try {
                player.sendSignChange(new Location(world, sign.x(), sign.y(), sign.z()), window, sign.color(),
                        sign.glowing());
            } catch (RuntimeException e) {
                ctx.logger().debug("Could not send dynamic sign '{}' to {}: {}", sign.name(), player.getName(),
                        e.getMessage());
            }
        }
    }

    private Component render(Player viewer, String line) {
        return ctx.messages().parseTrusted(line, Msg.unparsed("player", viewer.getName()));
    }

    /** A broken dynamic sign is removed from the store; it would otherwise be re-sent as a ghost. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!(block.getState() instanceof Sign)) {
            return;
        }
        Location location = block.getLocation();
        Optional<DynamicSignStore.DynamicSign> sign = store.at(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (sign.isEmpty()) {
            return;
        }
        ctx.scheduler().supplyIo(() -> store.remove(sign.get().name())).whenComplete((removed, error) -> {
            if (error != null) {
                ctx.logger().warn("A broken dynamic sign could not be removed from the store", error);
                return;
            }
            lastFrame.keySet().removeIf(key -> key.endsWith("|" + sign.get().name()));
            ctx.messages().send(event.getPlayer(), "signs.dynamic-removed-broken",
                    Msg.unparsed("name", sign.get().name()));
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID player = event.getPlayer().getUniqueId();
        lastFrame.keySet().removeIf(key -> key.startsWith(player + "|"));
    }

    /** Reads a sign's front side (lines, colour, glow) on the region thread that owns it. */
    static SignSnapshot snapshot(Sign sign) {
        var front = sign.getSide(Side.FRONT);
        List<String> lines = new ArrayList<>();
        for (Component line : front.lines()) {
            lines.add(SignText.plain(line));
        }
        return new SignSnapshot(lines, front.getColor(), front.isGlowingText());
    }

    record SignSnapshot(List<String> lines, org.bukkit.DyeColor color, boolean glowing) {}

    /** Parses an interval like {@code 1s} or a bare millisecond count. */
    static Optional<Long> parseInterval(String text, ConfigHandle<SignsConfig> config) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String value = text.strip().toLowerCase(Locale.ROOT);
        try {
            if (value.endsWith("ms")) {
                return Optional.of(Long.parseLong(value.substring(0, value.length() - 2).strip()));
            }
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
        Optional<java.time.Duration> duration = dev.yanianz.atrium.core.util.Durations.parse(value);
        long minimum = Math.max(100L, config.get().dynamicSignsMinIntervalMillis());
        return duration.map(d -> Math.max(minimum, d.toMillis()));
    }
}
