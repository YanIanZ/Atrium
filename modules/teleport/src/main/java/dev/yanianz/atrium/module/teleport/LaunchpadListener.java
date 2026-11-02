package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.core.config.ConfigHandle;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

/**
 * Launchpads: stepping on a configured pressure plate throws the player up and protects them from fall
 * damage for a while. Configured per block type in {@code launchpads.power}.
 */
final class LaunchpadListener implements Listener {
    static final String PERM_LAUNCHPADS = "atrium.launchpads";

    private final ConfigHandle<TeleportConfig> config;
    private final Map<UUID, Long> immuneUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastLaunch = new ConcurrentHashMap<>();

    LaunchpadListener(ConfigHandle<TeleportConfig> config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onStep(PlayerInteractEvent event) {
        if (event.getAction() != Action.PHYSICAL || event.getClickedBlock() == null) {
            return;
        }
        TeleportConfig.Launchpads cfg = config.get().launchpads;
        if (!cfg.enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(PERM_LAUNCHPADS)
                || !BlockPatterns.worldAllowed(cfg.worlds, event.getClickedBlock().getWorld().getName())) {
            return;
        }
        OptionalDouble power = Launchpads.power(cfg.power, event.getClickedBlock().getType().name());
        if (power.isEmpty() || throttled(player.getUniqueId(), Math.max(0, cfg.cooldownMillis))) {
            return;
        }
        launch(player, power.getAsDouble(), cfg);
    }

    /** Runs on the player's own thread (the interaction event does). */
    private void launch(Player player, double vertical, TeleportConfig.Launchpads cfg) {
        Vector velocity = new Vector(0, vertical, 0);
        double forward = cfg.forwardShare;
        if (forward != 0) {
            Vector direction = player.getLocation().getDirection().setY(0);
            if (direction.lengthSquared() > 0) {
                velocity.add(direction.normalize().multiply(vertical * forward));
            }
        }
        player.setVelocity(velocity);
        player.setFallDistance(0);
        if (cfg.fallImmunitySeconds > 0) {
            immuneUntil.put(player.getUniqueId(), System.currentTimeMillis() + cfg.fallImmunitySeconds * 1000L);
        }
    }

    /** Fall damage right after a launch is cancelled; the entry is dropped once its window ended. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Long until = immuneUntil.get(player.getUniqueId());
        if (until == null) {
            return;
        }
        if (System.currentTimeMillis() < until) {
            event.setCancelled(true);
            player.setFallDistance(0);
        } else {
            immuneUntil.remove(player.getUniqueId(), until);
        }
    }

    private boolean throttled(UUID player, long cooldownMillis) {
        long now = System.currentTimeMillis();
        boolean[] blocked = {false};
        lastLaunch.compute(player, (uuid, last) -> {
            if (last != null && cooldownMillis > 0 && now - last < cooldownMillis) {
                blocked[0] = true;
                return last;
            }
            return now;
        });
        return blocked[0];
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastLaunch.remove(uuid);
        immuneUntil.remove(uuid);
    }
}
