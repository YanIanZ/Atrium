package dev.yanianz.atrium.module.combat;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * /notarget: mobs do not pick a player as their target. The toggle is stored per player and restored on
 * the next join; the entity-target event is cancelled on the region thread that owns the mob.
 */
final class NotargetManager implements Listener {
    /** Radius used to release the mobs that are already targeting the player. */
    private static final double RELEASE_RADIUS = 48.0;

    private final ModuleContext ctx;
    private final ConfigHandle<CombatConfig> config;
    private final NotargetStore store;
    private final Set<UUID> enabled = ConcurrentHashMap.newKeySet();

    NotargetManager(ModuleContext ctx, ConfigHandle<CombatConfig> config, NotargetStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
    }

    boolean isEnabled(Player player) {
        return enabled.contains(player.getUniqueId());
    }

    /** Toggles the preference and releases the mobs that currently target the player. Player's thread only. */
    boolean toggle(Player player) {
        UUID id = player.getUniqueId();
        boolean next;
        if (enabled.remove(id)) {
            next = false;
        } else {
            enabled.add(id);
            next = true;
            releaseTargets(player);
        }
        ctx.users().user(player).update(store.component, ignored -> next);
        return next;
    }

    /** Drops the in-memory state after a quit; the stored value stays. */
    void forget(UUID id) {
        enabled.remove(id);
    }

    void shutdown() {
        enabled.clear();
    }

    private void releaseTargets(Player player) {
        try {
            for (Entity entity : player.getNearbyEntities(RELEASE_RADIUS, RELEASE_RADIUS, RELEASE_RADIUS)) {
                if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                    mob.setTarget(null);
                }
            }
        } catch (RuntimeException e) {
            // A nearby chunk may belong to another region while the player is moving; the target
            // event still stops every new target, so this only delays clearing an old one.
            ctx.logger().warn("Could not release mobs targeting {}: {}", player.getName(), e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entity(player, () -> {
            if (Boolean.TRUE.equals(ctx.users().user(player).get(store.component))) {
                enabled.add(player.getUniqueId());
            } else {
                enabled.remove(player.getUniqueId());
            }
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (!config.get().notarget.enabled || !(event.getTarget() instanceof Player player)) {
            return;
        }
        if (config.get().notarget.ignoreTempt && event.getReason() == EntityTargetEvent.TargetReason.TEMPT) {
            return;
        }
        if (enabled.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
