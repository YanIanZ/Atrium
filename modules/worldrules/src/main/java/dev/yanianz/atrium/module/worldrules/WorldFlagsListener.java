package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Ambient;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WaterMob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.world.PortalCreateEvent;

/**
 * World limits and gameplay flags that are not tied to one player's data: flight and damage immunity,
 * item handling, mob spawning, fire, explosions, leaves, farmland and portals. Every event runs on the
 * region that owns the player or block, so no state is touched across regions.
 */
final class WorldFlagsListener implements Listener {
    static final String FLY_BYPASS = "atrium.worldrules.fly.bypass";
    private static final String FLIGHT_OWNER = "worldrules";

    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    private final LeatherAnimationManager animation;
    private final Map<UUID, Long> notices = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warned = new ConcurrentHashMap<>();
    private final Map<List<String>, Set<WorldFlags.SpawnCategory>> spawnCache = new ConcurrentHashMap<>();

    WorldFlagsListener(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config, LeatherAnimationManager animation) {
        this.ctx = ctx;
        this.config = config;
        this.animation = animation;
    }

    private WorldRule rule(World world) {
        return config.get().rule(world);
    }

    // ---- flight and world limits ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entity(player, () -> {
            applyFlight(player);
            animation.start(player);
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entity(player, () -> applyFlight(player), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entityLater(player, () -> applyFlight(player), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        animation.stop(player.getUniqueId());
        FlightService flight = flight();
        if (flight != null) {
            flight.release(player, FLIGHT_OWNER);
        }
        notices.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || exempt(player) || rule(player.getWorld()).flightMode() != WorldRule.FlightMode.DENY) {
            return;
        }
        event.setCancelled(true);
        notice(player, "worldrules.fly-disabled");
    }

    /** Runs on the player's own thread. */
    void applyFlight(Player player) {
        if (!player.isOnline()) {
            return;
        }
        WorldRule rule = rule(player.getWorld());
        FlightService flight = flight();
        if (rule.flightMode() == WorldRule.FlightMode.ALLOW) {
            if (flight != null) {
                flight.claim(player, FLIGHT_OWNER);
            } else {
                warnOnce("the player module is not available; world fly mode ALLOW is inactive");
            }
        } else if (flight != null) {
            flight.release(player, FLIGHT_OWNER);
        }
        if (rule.flightMode() == WorldRule.FlightMode.DENY && player.isFlying() && !exempt(player)) {
            player.setFlying(false);
            notice(player, "worldrules.fly-disabled");
        }
    }

    private FlightService flight() {
        return ctx.services().get(FlightService.class).orElse(null);
    }

    private static boolean exempt(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.hasPermission(FLY_BYPASS);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        WorldRule rule = rule(event.getTo().getWorld());
        int roof = rule.flyAboveRoofHeight();
        if (roof <= 0 || exempt(player)) {
            return;
        }
        boolean over = event.getTo().getY() > roof;
        if (!over || !(player.isFlying() || player.isGliding())) {
            return;
        }
        event.setCancelled(true);
        player.setFlying(false);
        if (player.isGliding()) {
            player.setGliding(false);
        }
        notice(player, "worldrules.fly-above-roof");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (rule(player.getWorld()).godMode() == WorldRule.GodMode.ON) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnderPearl(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        World to = event.getTo() == null ? event.getFrom().getWorld() : event.getTo().getWorld();
        if (rule(event.getFrom().getWorld()).disableEnderPearls || rule(to).disableEnderPearls) {
            event.setCancelled(true);
            notice(event.getPlayer(), "worldrules.pearls-disabled");
        }
    }

    // ---- items ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (rule(event.getPlayer().getWorld()).disableItemDrop) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && rule(player.getWorld()).disableItemPickup) {
            event.setCancelled(true);
        }
    }

    // ---- mob spawning ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        WorldRule rule = rule(event.getLocation().getWorld());
        Set<WorldFlags.SpawnCategory> blocked = blocked(rule);
        if (blocked.isEmpty() && !rule.blockSpawnerMobs) {
            return;
        }
        boolean spawner = event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER
                || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER;
        if (WorldFlags.blocksSpawn(blocked, category(event.getEntity()), spawner, rule.blockSpawnerMobs)) {
            event.setCancelled(true);
        }
    }

    private Set<WorldFlags.SpawnCategory> blocked(WorldRule rule) {
        List<String> names = rule.blockMobSpawns;
        if (names == null || names.isEmpty()) {
            return Set.of();
        }
        return spawnCache.computeIfAbsent(names, values -> WorldFlags.spawnCategories(values, this::warnOnce));
    }

    private static WorldFlags.SpawnCategory category(Entity entity) {
        if (entity instanceof Enemy) {
            return WorldFlags.SpawnCategory.MONSTER;
        }
        if (entity instanceof Animals) {
            return WorldFlags.SpawnCategory.ANIMAL;
        }
        if (entity instanceof WaterMob) {
            return WorldFlags.SpawnCategory.WATER;
        }
        if (entity instanceof Ambient) {
            return WorldFlags.SpawnCategory.AMBIENT;
        }
        return entity instanceof Mob ? WorldFlags.SpawnCategory.OTHER : null;
    }

    // ---- fire, explosions, leaves, farmland, portals ----

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireSpread(BlockSpreadEvent event) {
        if (event.getSource().getType() != Material.FIRE || event.getNewState().getType() != Material.FIRE) {
            return;
        }
        if (rule(event.getBlock().getWorld()).disableFireSpread) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (rule(event.getLocation().getWorld()).disableExplosionBlockDamage) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (rule(event.getBlock().getWorld()).disableExplosionBlockDamage) {
            event.blockList().clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeafDecay(LeavesDecayEvent event) {
        if (rule(event.getBlock().getWorld()).disableLeafDecay) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTrample(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.PHYSICAL || event.getClickedBlock() == null) {
            return;
        }
        if (!WorldFlags.tramples(event.getClickedBlock().getType())) {
            return;
        }
        if (rule(event.getClickedBlock().getWorld()).disableCropTrample) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        if (rule(event.getWorld()).disablePortalCreation) {
            event.setCancelled(true);
        }
    }

    // ---- helpers ----

    /** Re-applies flight after a reload and drops the compiled spawn cache. */
    void reload() {
        spawnCache.clear();
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player, () -> applyFlight(player), null);
        }
    }

    /** Releases our flight claims when the module is disabled. */
    void shutdown() {
        FlightService flight = flight();
        if (flight != null) {
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                flight.release(player, FLIGHT_OWNER);
            }
        }
        notices.clear();
        spawnCache.clear();
    }

    private void notice(Player player, String key) {
        long now = System.currentTimeMillis();
        Long last = notices.get(player.getUniqueId());
        if (last != null && now - last < 2000L) {
            return;
        }
        notices.put(player.getUniqueId(), now);
        ctx.messages().send(player, key);
    }

    private void warnOnce(String message) {
        if (warned.putIfAbsent(message, Boolean.TRUE) == null) {
            ctx.logger().warn("worldrules: {}", message);
        }
    }
}
