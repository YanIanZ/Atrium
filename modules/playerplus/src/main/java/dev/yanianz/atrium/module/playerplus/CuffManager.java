package dev.yanianz.atrium.module.playerplus;

import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.jspecify.annotations.Nullable;

/**
 * Cuffs: confinement around a stored point (not a live Location), a command whitelist, no interaction and
 * no outgoing damage. State is persisted per player. The point is an immutable record, so movement checks
 * on the player's region thread never touch mutable shared data.
 */
final class CuffManager implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<PlayerPlusConfig> config;
    private final PlayerPlusStore store;
    private final Set<UUID> cuffed = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CuffPoint> points = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastNotice = new ConcurrentHashMap<>();

    CuffManager(ModuleContext ctx, ConfigHandle<PlayerPlusConfig> config, PlayerPlusStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
    }

    boolean cuffed(UUID uuid) {
        return cuffed.contains(uuid);
    }

    @Nullable
    CuffPoint point(UUID uuid) {
        return points.get(uuid);
    }

    /** Stores the cuff and updates the in-memory index. Safe from any thread. */
    void apply(AtriumUser user, CuffPoint point) {
        user.update(store.component, state -> state.withCuff(point));
        cuffed.add(user.uuid());
        points.put(user.uuid(), point);
    }

    /** Removes the cuff and the in-memory index. Safe from any thread. */
    void clear(AtriumUser user) {
        user.update(store.component, state -> state.withCuff(null));
        cuffed.remove(user.uuid());
        points.remove(user.uuid());
        lastNotice.remove(user.uuid());
    }

    /** Moves the confinement point to a new position (respawn, first join of an offline cuff). */
    void moveTo(AtriumUser user, Location location, String by) {
        apply(user, CuffPoint.at(location, by));
    }

    double radius() {
        return Math.max(0, config.get().cuff.radius);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entity(player, () -> {
            AtriumUser user = ctx.users().user(player);
            PlayerPlusState state = user.get(store.component);
            if (!state.cuffed()) {
                cuffed.remove(player.getUniqueId());
                points.remove(player.getUniqueId());
                return;
            }
            if (player.hasPermission(PlayerPlusCommands.PERM_CUFF_EXEMPT)) {
                user.update(store.component, value -> value.withCuff(null));
                cuffed.remove(player.getUniqueId());
                points.remove(player.getUniqueId());
                return;
            }
            CuffPoint point = state.cuff();
            if (point == null || point.world().isEmpty()) {
                // Cuffed while offline: the confinement starts where the player appears.
                point = CuffPoint.at(player.getLocation(), "server");
                CuffPoint created = point;
                user.update(store.component, value -> value.withCuff(created));
            }
            cuffed.add(player.getUniqueId());
            points.put(player.getUniqueId(), point);
            if (player.getVehicle() != null) {
                player.leaveVehicle();
            }
            ctx.messages().send(player, "playerplus.cuff-notice-online");
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cuffed.remove(uuid);
        points.remove(uuid);
        lastNotice.remove(uuid);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) {
            return;
        }
        Player player = event.getPlayer();
        if (!cuffed(player.getUniqueId())) {
            return;
        }
        CuffPoint point = points.get(player.getUniqueId());
        if (point == null) {
            return;
        }
        Location to = event.getTo();
        if (CuffRules.inside(point, worldName(to), to.getX(), to.getY(), to.getZ(), radius())) {
            return;
        }
        Location back = event.getFrom().clone();
        back.setYaw(to.getYaw());
        back.setPitch(to.getPitch());
        // Never freeze a falling player in the air: gravity may move them down, but not away horizontally.
        if (to.getY() < back.getY()) {
            back.setY(to.getY());
        }
        event.setTo(back);
        notice(player, "cuff-move-blocked", true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!cuffed(player.getUniqueId())) {
            return;
        }
        CuffPoint point = points.get(player.getUniqueId());
        if (point == null) {
            return;
        }
        Location to = event.getTo();
        if (!CuffRules.inside(point, worldName(to), to.getX(), to.getY(), to.getZ(), radius())) {
            event.setCancelled(true);
            notice(player, "cuff-teleport-blocked", false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!cuffed(player.getUniqueId())) {
            return;
        }
        Location location = event.getRespawnLocation();
        AtriumUser user = ctx.users().user(player);
        moveTo(user, location, "respawn");
        notice(player, "cuff-respawn", false);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!cuffed(player.getUniqueId())) {
            return;
        }
        if (CuffRules.commandAllowed(event.getMessage(), config.get().cuff.allowedCommands)) {
            return;
        }
        event.setCancelled(true);
        notice(player, "cuff-command-blocked", false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.PHYSICAL) {
            return;
        }
        if (cuffed(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            notice(event.getPlayer(), "cuff-interact-blocked", true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (cuffed(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            notice(event.getPlayer(), "cuff-interact-blocked", true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (cuffed(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            notice(event.getPlayer(), "cuff-interact-blocked", true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (cuffed(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            notice(event.getPlayer(), "cuff-interact-blocked", true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicle(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && cuffed(player.getUniqueId())) {
            event.setCancelled(true);
            notice(player, "cuff-interact-blocked", true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        Player attacker = null;
        if (damager instanceof Player player) {
            attacker = player;
        } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker != null && cuffed(attacker.getUniqueId())) {
            event.setCancelled(true);
            notice(attacker, "cuff-damage-blocked", true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player player && cuffed(player.getUniqueId())) {
            event.setCancelled(true);
            notice(player, "cuff-damage-blocked", true);
        }
    }

    private static String worldName(Location location) {
        return location.getWorld() == null ? "" : location.getWorld().getName();
    }

    private void notice(Player player, String key, boolean actionBar) {
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, config.get().cuff.noticeCooldownMillis);
        Long previous = lastNotice.get(player.getUniqueId());
        if (previous != null && now - previous < cooldown) {
            return;
        }
        lastNotice.put(player.getUniqueId(), now);
        if (actionBar) {
            ctx.messages().actionBar(player, "playerplus." + key);
        } else {
            ctx.messages().send(player, "playerplus." + key);
        }
    }

    void disable() {
        cuffed.clear();
        points.clear();
        lastNotice.clear();
    }
}
