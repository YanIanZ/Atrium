package dev.yanianz.atrium.module.seating;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;

/**
 * Crawling with the vanilla swimming pose. A barrier block is sent only to the crawling client at
 * the block its head would occupy; the world is never modified. The barrier is resent whenever the
 * player's feet block changes and restored when the crawl ends, so no fake block survives a
 * teleport, a world change or a disconnect.
 */
final class CrawlManager {
    private record Session(CrawlRules.Position position, BlockData original) {}

    private final ModuleContext ctx;
    private final ConfigHandle<SeatingConfig> config;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    CrawlManager(ModuleContext ctx, ConfigHandle<SeatingConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    void toggle(Player player, CommandSender sender) {
        if (closed) {
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            stopNow(player, CrawlRules.StopReason.TOGGLE);
            done(sender, player, "crawl-stopped");
            return;
        }
        start(player, sender);
    }

    void start(Player player, CommandSender sender) {
        if (closed) {
            return;
        }
        SeatingConfig cfg = config.get();
        if (!cfg.crawl) {
            fail(sender, "feature-disabled");
            return;
        }
        if (SeatManager.worldDisabled(player, cfg)) {
            fail(sender, "world-disabled");
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            return;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) {
            fail(sender, "spectator");
            return;
        }
        if (!CrawlRules.canStart(player.isSneaking(), player.isInsideVehicle(), player.isFlying(),
                player.isGliding(), player.isDead(), player.getGameMode() == GameMode.SPECTATOR,
                player.isInWater())) {
            if (player.isSneaking()) {
                fail(sender, "crawl-sneaking");
            } else if (player.isInsideVehicle()) {
                fail(sender, "already-riding");
            } else if (player.isFlying() || player.isGliding()) {
                fail(sender, "crawl-airborne");
            } else if (player.isInWater()) {
                fail(sender, "crawl-water");
            } else {
                fail(sender, "cannot-crawl-here");
            }
            return;
        }
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            fail(sender, "cannot-crawl-here");
            return;
        }
        CrawlRules.Position position = position(location);
        if (!apply(player, position)) {
            fail(sender, "cannot-crawl-here");
            return;
        }
        player.setPose(Pose.SWIMMING, true);
        done(sender, player, "crawl-started");
    }

    /** PlayerMoveEvent, already on the player's thread. */
    void onMove(Player player, Location from, Location to) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        SeatingConfig.CrawlOptions options = options();
        if (options.stopOnJump && CrawlRules.jumped(from.getY(), to.getY(), player.isInWater())) {
            stop(player, CrawlRules.StopReason.JUMP);
            return;
        }
        if (CrawlRules.shouldStop(player.isDead(), player.getGameMode() == GameMode.SPECTATOR,
                player.isFlying(), player.isGliding(), player.isInsideVehicle())) {
            stop(player, player.isInsideVehicle() ? CrawlRules.StopReason.VEHICLE
                    : player.isFlying() || player.isGliding() ? CrawlRules.StopReason.FLIGHT
                    : CrawlRules.StopReason.GAMEMODE);
            return;
        }
        if (to.getWorld() == null) {
            stop(player, CrawlRules.StopReason.INVALID);
            return;
        }
        CrawlRules.Position position = position(to);
        if (CrawlRules.sameBlock(session.position(), position.world(), position.x(), position.y(),
                position.z())) {
            return;
        }
        // Restore the old fake block before faking the new one so the client never keeps both.
        restore(player, session);
        if (!apply(player, position)) {
            stop(player, CrawlRules.StopReason.INVALID);
            return;
        }
        player.setPose(Pose.SWIMMING, true);
    }

    void onSneak(Player player, boolean sneaking) {
        if (!sneaking || !options().stopOnSneak) {
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            stop(player, CrawlRules.StopReason.SNEAK);
        }
    }

    void onDamage(Player player) {
        if (!options().stopOnDamage) {
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            stop(player, CrawlRules.StopReason.DAMAGE);
        }
    }

    void onFlight(Player player) {
        if (!options().stopOnFlight) {
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            stop(player, CrawlRules.StopReason.FLIGHT);
        }
    }

    boolean isCrawling(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /** Stops a crawl and reports it when the reason is user-visible (sneak, jump, command). */
    void stop(Player player, CrawlRules.StopReason reason) {
        boolean wasCrawling = sessions.containsKey(player.getUniqueId());
        stopNow(player, reason);
        if (wasCrawling && reason.notifies() && player.isOnline()) {
            ctx.messages().send(player, "seating.crawl-stopped");
        }
    }

    private boolean apply(Player player, CrawlRules.Position position) {
        World world = Bukkit.getWorld(position.world());
        if (world == null) {
            return false;
        }
        Block above = world.getBlockAt(position.x(), CrawlRules.barrierY(position.y()), position.z());
        BlockData original = null;
        if (above.isPassable() && !above.getType().isSolid()) {
            original = above.getBlockData();
            player.sendBlockChange(above.getLocation(), Material.BARRIER.createBlockData());
        }
        sessions.put(player.getUniqueId(), new Session(position, original));
        return true;
    }

    /** Ends a crawl on this thread and restores the client's view. */
    private void stopNow(Player player, CrawlRules.StopReason reason) {
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        // Only a disconnect leaves no client behind to correct. On death the player respawns with the same
        // client, so the fake barrier must be removed there too instead of hanging around after respawn.
        if (reason != CrawlRules.StopReason.QUIT) {
            restore(player, session);
        }
        if (player.isOnline() && !player.isDead()) {
            player.setPose(Pose.STANDING, false);
        }
    }

    private void restore(Player player, Session session) {
        if (session.original() == null) {
            return;
        }
        World world = Bukkit.getWorld(session.position().world());
        if (world == null || world != player.getWorld()) {
            return; // the client already left that dimension
        }
        int x = session.position().x();
        int z = session.position().z();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return; // the client unloaded the chunk with the fake block
        }
        int y = CrawlRules.barrierY(session.position().y());
        Location location = new Location(world, x, y, z);
        // Resend the block as it is now when this region owns it (it may have changed during the crawl);
        // the start snapshot is only a fallback for a block this thread must not read.
        BlockData data = Bukkit.isOwnedByCurrentRegion(world, x, z)
                ? world.getBlockAt(x, y, z).getBlockData()
                : session.original();
        player.sendBlockChange(location, data);
    }

    void shutdown() {
        closed = true;
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                sessions.remove(id);
                continue;
            }
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                stopNow(player, CrawlRules.StopReason.DISABLED);
            } else {
                ctx.scheduler().entity(player, () -> stopNow(player, CrawlRules.StopReason.DISABLED), null);
            }
        }
    }

    private SeatingConfig.CrawlOptions options() {
        SeatingConfig cfg = config.get();
        return cfg.crawlOptions == null ? new SeatingConfig.CrawlOptions() : cfg.crawlOptions;
    }

    private static CrawlRules.Position position(Location location) {
        return new CrawlRules.Position(location.getWorld().getName(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ());
    }

    private void done(CommandSender sender, Player player, String key, TagResolver... tags) {
        send(player, key, tags);
        if (!sender.equals(player)) {
            send(sender, key + "-other", TagResolver.resolver(
                    Msg.unparsed("player", player.getName()), TagResolver.resolver(tags)));
        }
    }

    private void fail(CommandSender recipient, String key, TagResolver... tags) {
        send(recipient, key, tags);
    }

    private void send(CommandSender recipient, String key, TagResolver... tags) {
        String fullKey = "seating." + key;
        if (recipient instanceof Player player) {
            if (Bukkit.isOwnedByCurrentRegion(player)) {
                ctx.messages().send(player, fullKey, tags);
            } else {
                ctx.scheduler().entity(player, () -> ctx.messages().send(player, fullKey, tags), null);
            }
        } else {
            ctx.messages().send(recipient, fullKey, tags);
        }
    }
}
