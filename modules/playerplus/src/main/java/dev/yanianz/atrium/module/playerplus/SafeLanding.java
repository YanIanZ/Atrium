package dev.yanianz.atrium.module.playerplus;

import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Applies the short slow-falling grace used when a flight source ends in mid-air. */
final class SafeLanding {
    private SafeLanding() {}

    static void apply(Player player, int seconds) {
        if (seconds <= 0 || !player.isOnline() || player.isDead()) {
            return;
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, seconds * 20, 0, false, true, true));
    }
}
