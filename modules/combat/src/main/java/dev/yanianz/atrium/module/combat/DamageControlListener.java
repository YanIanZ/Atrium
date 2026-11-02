package dev.yanianz.atrium.module.combat;

import dev.yanianz.atrium.core.config.ConfigHandle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier;

/** Per-cause damage multipliers selected by permission. */
final class DamageControlListener implements Listener {
    static final String BYPASS_PERMISSION = "atrium.combat.damage.bypass";

    private final ConfigHandle<CombatConfig> config;

    DamageControlListener(ConfigHandle<CombatConfig> config) {
        this.config = config;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        CombatConfig.DamageControl control = config.get().damageControl;
        if (!control.enabled || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (player.hasPermission(BYPASS_PERMISSION)) {
            return;
        }
        double multiplier = DamageControlRules.multiplier(control.rules, event.getCause().name(),
                player.getWorld().getName(), player::hasPermission);
        if (multiplier == 1.0) {
            return;
        }
        if (multiplier <= 0.0) {
            event.setCancelled(true);
            return;
        }
        scale(event, multiplier);
    }

    /**
     * Scales every damage modifier, so the final damage is multiplied exactly once. Only changing the base
     * damage would let armour and resistance be applied a second time on top of an already reduced value.
     */
    private static void scale(EntityDamageEvent event, double multiplier) {
        for (DamageModifier modifier : DamageModifier.values()) {
            if (modifier == DamageModifier.BASE || !event.isApplicable(modifier)) {
                continue;
            }
            event.setDamage(modifier, event.getDamage(modifier) * multiplier);
        }
        event.setDamage(DamageModifier.BASE, event.getDamage(DamageModifier.BASE) * multiplier);
    }
}
