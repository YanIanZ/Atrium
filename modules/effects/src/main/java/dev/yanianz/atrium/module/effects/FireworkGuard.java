package dev.yanianz.atrium.module.effects;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Effect fireworks are cosmetic. They carry a persistent-data marker, which is checked on the damaged
 * entity's own region thread, so the damage event is cancelled even on a region whose thread did not see
 * the spawn.
 *
 * <p>There is deliberately no in-memory UUID set: the removal task runs after the firework is already
 * retired, so an unmark there never happened and the set grew forever (B-P5). The PDC marker lives and
 * dies with the entity. The cleanup task in {@code EffectRuntime} still removes the entity two ticks after
 * the detonation, so no firework entity is left behind per render.
 */
final class FireworkGuard implements Listener {
    private final NamespacedKey key;

    FireworkGuard(NamespacedKey key) {
        this.key = key;
    }

    boolean isOurs(Entity entity) {
        if (!(entity instanceof Firework firework)) {
            return false;
        }
        return firework.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework firework && isOurs(firework)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplosionDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION
                && event.getEntity() instanceof Firework firework && isOurs(firework)) {
            event.setCancelled(true);
        }
    }
}
