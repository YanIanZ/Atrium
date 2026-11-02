package dev.yanianz.atrium.module.worldtools;

import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Mirror building: while a player has mirror mode on, every block they place or break is reproduced at
 * the transformed position around their centre.
 *
 * <p>Every target block is changed on the region thread that owns it, after a protection check there.
 * Placed copies cost one item each in survival (see {@link MirrorCost}) and broken copies never drop
 * anything, so mirroring cannot duplicate items. A cheap particle marker shows the owner the centre.
 */
final class MirrorBuilding implements Listener {
    /** Permission that makes mirrored blocks free in survival as well. */
    static final String PERM_BYPASS_COST = "atrium.mirror.bypass-cost";

    record Session(String world, int x, int y, int z, MirrorTransform transform) {}

    private final ModuleContext ctx;
    private final ConfigHandle<WorldToolsConfig> config;
    private final ProtectionService protection;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, MirrorRateLimiter> limiters = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandle> markers = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rateWarnings = new ConcurrentHashMap<>();

    MirrorBuilding(ModuleContext ctx, ConfigHandle<WorldToolsConfig> config, ProtectionService protection) {
        this.ctx = ctx;
        this.config = config;
        this.protection = protection;
    }

    Optional<Session> session(UUID player) {
        return Optional.ofNullable(sessions.get(player));
    }

    /** Starts (or replaces) the mode for a player; the caller already resolved the centre and transform. */
    void start(Player player, String world, int x, int y, int z, MirrorTransform transform) {
        UUID id = player.getUniqueId();
        sessions.put(id, new Session(world, x, y, z, transform));
        restartMarker(player);
    }

    /** Moves the centre, keeping the transform. */
    void move(Player player, String world, int x, int y, int z) {
        UUID id = player.getUniqueId();
        Session current = sessions.get(id);
        if (current == null) {
            return;
        }
        sessions.put(id, new Session(world, x, y, z, current.transform()));
    }

    void setTransform(UUID player, MirrorTransform transform) {
        sessions.computeIfPresent(player,
                (id, current) -> new Session(current.world(), current.x(), current.y(), current.z(), transform));
    }

    boolean stop(UUID player) {
        boolean wasActive = sessions.remove(player) != null;
        TaskHandle marker = markers.remove(player);
        if (marker != null) {
            marker.cancel();
        }
        limiters.remove(player);
        rateWarnings.remove(player);
        return wasActive;
    }

    void shutdown() {
        for (UUID id : sessions.keySet()) {
            stop(id);
        }
    }

    // ---- marker ---------------------------------------------------------------------------------

    private void restartMarker(Player player) {
        UUID id = player.getUniqueId();
        TaskHandle previous = markers.remove(id);
        if (previous != null) {
            previous.cancel();
        }
        WorldToolsConfig cfg = config.get();
        if (!cfg.mirrorMarkerParticles()) {
            return;
        }
        int period = cfg.mirrorMarkerIntervalTicks();
        // Runs on the player's own thread and only sends a particle to that player: cheap and safe.
        TaskHandle handle = ctx.track(ctx.scheduler().entityTimer(player, () -> {
            Session session = sessions.get(id);
            if (session == null || !player.isOnline() || !player.getWorld().getName().equals(session.world())) {
                return;
            }
            if (!config.get().mirrorMarkerParticles()) {
                return;
            }
            player.spawnParticle(Particle.END_ROD, session.x() + 0.5, session.y() + 1.0, session.z() + 0.5,
                    1, 0.0, 0.0, 0.0, 0.0);
        }, () -> markers.remove(id), period, period));
        markers.put(id, handle);
    }

    // ---- placement ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        WorldToolsConfig cfg = config.get();
        if (!cfg.mirrorEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        if (!sessions.containsKey(player.getUniqueId())) {
            return;
        }
        Block source = event.getBlockPlaced();
        if (source.getWorld() == null) {
            return;
        }
        // Snapshot the placed block here; the inventory work below runs on the player's own thread.
        Material material = source.getType();
        BlockData data;
        try {
            data = source.getBlockData().clone();
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot read the block data of {} for mirror building at {}", material.getKey(),
                    source.getLocation(), e);
            return;
        }
        Location placed = source.getLocation();
        ctx.scheduler().entity(player, () -> mirrorPlace(player, placed, material, data), null);
    }

    /** Player thread: decides the target and the cost, then hands the target block to its region thread. */
    private void mirrorPlace(Player player, Location source, Material material, BlockData sourceData) {
        WorldToolsConfig cfg = config.get();
        Session session = sessions.get(player.getUniqueId());
        World world = source.getWorld();
        if (session == null || world == null || !session.world().equals(world.getName())) {
            return;
        }
        Optional<MirrorTargets.Target> maybe = MirrorTargets.target(session.transform(), session.x(), session.y(),
                session.z(), source.getBlockX(), source.getBlockY(), source.getBlockZ(), cfg.mirrorMaxRadius());
        if (maybe.isEmpty()) {
            return;
        }
        MirrorTargets.Target target = maybe.get();
        int pay = MirrorCost.itemsToConsume(player.getGameMode() == GameMode.CREATIVE,
                player.hasPermission(PERM_BYPASS_COST), cfg.mirrorChargeSurvival(), cfg.mirrorChargeCreative(), 1,
                countItems(player.getInventory(), material));
        if (pay == MirrorCost.NOT_ENOUGH) {
            ctx.messages().send(player, "worldtools.mirror-no-items", Msg.unparsed("item", key(material)),
                    Msg.number("count", 1));
            return;
        }
        if (!acquire(player, cfg)) {
            return;
        }
        if (pay > 0 && !takeItems(player.getInventory(), material, pay)) {
            ctx.messages().send(player, "worldtools.mirror-no-items", Msg.unparsed("item", key(material)),
                    Msg.number("count", 1));
            return;
        }
        BlockData data;
        try {
            data = MirrorBlocks.transform(sourceData, session.transform());
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot transform block data of {} for mirror building at {}", material.getKey(),
                    source, e);
            refund(player, world, target, material, pay);
            return;
        }
        Location location = new Location(world, target.x(), target.y(), target.z());
        try {
            ctx.scheduler().region(location, () -> place(player, world, target, data, material, pay));
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot schedule a mirror placement at {} for {}", location, player.getName(), e);
            refund(player, world, target, material, pay);
        }
    }

    /** Region thread of the target block. */
    private void place(Player player, World world, MirrorTargets.Target target, BlockData data, Material material,
            int paid) {
        try {
            if (!player.isOnline() || target.y() < world.getMinHeight() || target.y() >= world.getMaxHeight()
                    || !world.isChunkLoaded(target.x() >> 4, target.z() >> 4)) {
                refund(player, world, target, material, paid);
                return;
            }
            Block block = world.getBlockAt(target.x(), target.y(), target.z());
            if (!(block.isEmpty() || block.isReplaceable())
                    || (protection != null && !protection.canBuild(player, block.getLocation()))) {
                refund(player, world, target, material, paid);
                return;
            }
            block.setBlockData(data, false);
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot mirror a placed block at {} for {}", world.getName() + " " + target, player.getName(), e);
            refund(player, world, target, material, paid);
        }
    }

    // ---- breaking -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        WorldToolsConfig cfg = config.get();
        if (!cfg.mirrorEnabled()) {
            return;
        }
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        Block source = event.getBlock();
        if (source.getWorld() == null || !session.world().equals(source.getWorld().getName())) {
            return;
        }
        Optional<MirrorTargets.Target> maybe = MirrorTargets.target(session.transform(), session.x(), session.y(),
                session.z(), source.getX(), source.getY(), source.getZ(), cfg.mirrorMaxRadius());
        if (maybe.isEmpty() || !acquire(player, cfg)) {
            return;
        }
        MirrorTargets.Target target = maybe.get();
        Material expected = source.getType();
        World world = source.getWorld();
        Location location = new Location(world, target.x(), target.y(), target.z());
        try {
            ctx.scheduler().region(location, () -> {
                try {
                    if (!player.isOnline() || target.y() < world.getMinHeight()
                            || target.y() >= world.getMaxHeight()
                            || !world.isChunkLoaded(target.x() >> 4, target.z() >> 4)) {
                        return;
                    }
                    Block block = world.getBlockAt(target.x(), target.y(), target.z());
                    // Only the same block: never delete a different build that happens to be in the way.
                    if (block.getType() != expected) {
                        return;
                    }
                    if (protection != null && !protection.canBreak(player, block)) {
                        return;
                    }
                    // No drops, no BlockBreakEvent: the original break already gave its drops.
                    block.setType(Material.AIR, false);
                } catch (RuntimeException e) {
                    ctx.logger().warn("Cannot mirror a broken block at {} for {}", location, player.getName(), e);
                }
            });
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot schedule a mirrored break at {} for {}", location, player.getName(), e);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer().getUniqueId());
    }

    // ---- items ----------------------------------------------------------------------------------

    private boolean acquire(Player player, WorldToolsConfig cfg) {
        MirrorRateLimiter limiter = limiters.computeIfAbsent(player.getUniqueId(), id -> new MirrorRateLimiter());
        if (limiter.tryAcquire(System.nanoTime(), 1, cfg.mirrorRatePerSecond(), cfg.mirrorRateBurst())) {
            return true;
        }
        long now = System.nanoTime();
        Long last = rateWarnings.get(player.getUniqueId());
        if (last == null || now - last > 2_000_000_000L) {
            rateWarnings.put(player.getUniqueId(), now);
            ctx.messages().send(player, "worldtools.mirror-rate-limited",
                    Msg.number("rate", cfg.mirrorRatePerSecond()));
        }
        return false;
    }

    static int countItems(PlayerInventory inventory, Material material) {
        int total = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    /** Removes exactly {@code amount} items of the material; puts everything back when that is impossible. */
    static boolean takeItems(PlayerInventory inventory, Material material, int amount) {
        if (amount <= 0) {
            return true;
        }
        int remaining = amount;
        for (int slot = 0; slot < inventory.getSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(stack.getAmount(), remaining);
            ItemStack updated = stack.clone();
            updated.setAmount(updated.getAmount() - take);
            inventory.setItem(slot, updated.getAmount() <= 0 ? null : updated);
            remaining -= take;
        }
        if (remaining > 0) {
            inventory.addItem(new ItemStack(material, amount - remaining));
            return false;
        }
        return true;
    }

    /** Gives a reserved item back when the copy could not be placed. Never loses the item. */
    private void refund(Player player, World world, MirrorTargets.Target target, Material material, int amount) {
        if (amount <= 0 || !material.isItem()) {
            return;
        }
        try {
            ctx.scheduler().entity(player, () -> give(player, world, target, material, amount),
                    () -> drop(world, target, material, amount));
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot refund {} x{} after a skipped mirror block for {}", material.getKey(), amount,
                    player.getName(), e);
            drop(world, target, material, amount);
        }
    }

    /** Player thread. */
    private void give(Player player, World world, MirrorTargets.Target target, Material material, int amount) {
        if (!player.isOnline()) {
            drop(world, target, material, amount);
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(material, amount));
        for (ItemStack stack : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), stack);
        }
    }

    /** Player left before the refund: drop it on the region thread that owns the target block. */
    private void drop(World world, MirrorTargets.Target target, Material material, int amount) {
        Location at = new Location(world, target.x() + 0.5, target.y() + 0.5, target.z() + 0.5);
        try {
            ctx.scheduler().region(at, () -> world.dropItemNaturally(at, new ItemStack(material, amount)));
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot drop the refund of {} x{} at {}", material.getKey(), amount, at, e);
        }
    }

    private static String key(Material material) {
        return material.getKey().getKey();
    }
}
