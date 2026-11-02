package dev.yanianz.atrium.module.mobs;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.Nullable;

/**
 * Head drops for player kills and mob kills. The chance is one roll per death: base chance plus a looting
 * bonus, clamped to 100, and at most one head is added to the drops.
 */
final class HeadDrops implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<MobsConfig> config;

    HeadDrops(ModuleContext ctx, ConfigHandle<MobsConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    /** Runs on the dead entity's own region thread, before the drops are handed out. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        MobsConfig cfg = config.get();
        MobsConfig.Heads heads = cfg.heads;
        if (!heads.enabled) {
            return;
        }
        if (event instanceof PlayerDeathEvent playerDeath
                && playerDeath.getKeepInventory() && !heads.dropWithKeepInventory) {
            return;
        }
        LivingEntity victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer != null && killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        if (heads.onlyPlayerKills && killer == null) {
            return;
        }
        double base = victim instanceof Player
                ? cfg.playerHeadChance()
                : configuredChance(heads, victim.getType());
        if (base <= 0) {
            return;
        }
        ItemStack head = headOf(victim);
        if (head == null) {
            return;
        }
        double chance = HeadDropRules.chance(base, lootingLevel(killer), cfg.lootingBonus());
        if (!HeadDropRules.drops(chance, ThreadLocalRandom.current().nextDouble(100))) {
            return;
        }
        event.getDrops().add(head);
    }

    private static double configuredChance(MobsConfig.Heads heads, EntityType type) {
        for (var entry : heads.entityChances.entrySet()) {
            Double value = entry.getValue();
            if (value == null || entry.getKey() == null) {
                continue;
            }
            boolean matches = EntityTypes.parse(entry.getKey()).map(parsed -> parsed == type).orElse(false);
            if (matches) {
                return Double.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
            }
        }
        return 0;
    }

    private static int lootingLevel(@Nullable Player killer) {
        if (killer == null) {
            return 0;
        }
        ItemStack tool = killer.getInventory().getItemInMainHand();
        return tool.getEnchantmentLevel(Enchantment.LOOTING);
    }

    private static @Nullable ItemStack headOf(LivingEntity victim) {
        if (victim instanceof Player player) {
            ItemStack head = ItemStack.of(Material.PLAYER_HEAD);
            head.editMeta(SkullMeta.class, meta -> {
                meta.setPlayerProfile(player.getPlayerProfile());
                meta.displayName(Component.text(player.getName())
                        .decoration(TextDecoration.ITALIC, false));
            });
            return head;
        }
        Material material = HeadMaterials.of(victim.getType());
        return material == null ? null : ItemStack.of(material);
    }
}
