package dev.yanianz.atrium.module.combat;

import dev.yanianz.atrium.api.protection.ProtectionService;
import dev.yanianz.atrium.api.teleport.AtriumPreTeleportEvent;
import dev.yanianz.atrium.api.teleport.TeleportService;
import dev.yanianz.atrium.api.vanish.VanishStateChangeEvent;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

/** PvP tagging, its command/flight/teleport restrictions and the combat-log punishment. */
final class CombatListener implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<CombatConfig> config;
    private final CombatTracker tracker;
    private final @Nullable ProtectionService protection;
    private final @Nullable VanishService vanish;

    CombatListener(ModuleContext ctx, ConfigHandle<CombatConfig> config, CombatTracker tracker,
            @Nullable ProtectionService protection, @Nullable VanishService vanish) {
        this.ctx = ctx;
        this.config = config;
        this.tracker = tracker;
        this.protection = protection;
        this.vanish = vanish;
    }

    // ---- tagging ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        CombatConfig.Tag tag = config.get().tag;
        if (!tag.enabled || !(event.getEntity() instanceof LivingEntity victim)) {
            return;
        }
        Player attacker = attacker(event.getDamager());
        if (victim instanceof Player victimPlayer) {
            if (attacker != null && !attacker.equals(victimPlayer)) {
                if (!pvpAllowed(attacker, victimPlayer)) {
                    return;
                }
                long millis = tag.seconds() * 1000L;
                tagLater(attacker, millis);
                if (tag.tagVictim) {
                    tagLater(victimPlayer, millis);
                }
                return;
            }
            if (attacker == null && tag.mobCombat && mobDamager(event.getDamager())) {
                tagLater(victimPlayer, tag.mobSeconds() * 1000L);
            }
            return;
        }
        if (attacker != null && tag.mobCombat) {
            tagLater(attacker, tag.mobSeconds() * 1000L);
        }
    }

    /** Tags on the player's own thread: the attacker of a hit may live in another region. */
    private void tagLater(Player player, long millis) {
        ctx.scheduler().entity(player, () -> tracker.tagNow(player, millis), null);
    }

    /** A fight only tags when the world allows combat, nobody is vanished and protection allows it. */
    private boolean pvpAllowed(Player attacker, Player victim) {
        if (!config.get().worldEnabled(victim.getWorld().getName())) {
            return false;
        }
        if (attacker.hasPermission(CombatTracker.BYPASS_PERMISSION)
                || victim.hasPermission(CombatTracker.BYPASS_PERMISSION)) {
            return false;
        }
        if (vanish != null && (vanish.isVanished(attacker.getUniqueId())
                || vanish.isVanished(victim.getUniqueId()))) {
            return false;
        }
        if (protection == null || !config.get().tag.respectProtection) {
            return true;
        }
        // ProtectionService has no PvP flag; being unable to interact at the victim's position is the
        // available sign that the region denies the fight. Damage cancelled by the region plugin never
        // reaches this listener at all because it runs at MONITOR with ignoreCancelled.
        return protection.canInteract(attacker, victim.getLocation());
    }

    private static @Nullable Player attacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player player) {
            return player;
        }
        return null;
    }

    /** Whether the damager is a mob or a projectile a mob shot; blocks and TNT do not count. */
    private static boolean mobDamager(Entity damager) {
        if (damager instanceof Player) {
            return false;
        }
        if (damager instanceof LivingEntity) {
            return true;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof LivingEntity shooter) {
            return !(shooter instanceof Player);
        }
        return false;
    }

    // ---- restrictions while tagged ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        CombatConfig.Tag tag = config.get().tag;
        Player player = event.getPlayer();
        if (!tag.enabled || !tag.blockCommands || !tracker.isTagged(player.getUniqueId())) {
            return;
        }
        if (player.hasPermission(CombatTracker.BYPASS_PERMISSION)
                || !config.get().worldEnabled(player.getWorld().getName())) {
            return;
        }
        if (!CombatRules.blocksCommand(event.getMessage(), tag.blockedCommands, tag.allowedCommands)) {
            return;
        }
        event.setCancelled(true);
        ctx.messages().send(player, "combat.tag-command-blocked",
                Msg.unparsed("command", CombatRules.commandRoot(event.getMessage())));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (!config.get().tag.blockFlight || !tracker.isTagged(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getPlayer().hasPermission(CombatTracker.BYPASS_PERMISSION)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!config.get().tag.blockElytra || !event.isGliding()
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!tracker.isTagged(player.getUniqueId()) || player.hasPermission(CombatTracker.BYPASS_PERMISSION)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreTeleport(AtriumPreTeleportEvent event) {
        CombatConfig.Tag tag = config.get().tag;
        Player player = event.getPlayer();
        if (!tag.enabled || !tag.cancelTeleports || !tracker.isTagged(player.getUniqueId())) {
            return;
        }
        if (player.hasPermission(CombatTracker.BYPASS_PERMISSION)) {
            return;
        }
        TeleportService.TeleportCause cause = event.getCause();
        if (cause == TeleportService.TeleportCause.PLUGIN || cause == TeleportService.TeleportCause.DEATH_BACK) {
            return;
        }
        event.setCancelled(true);
        ctx.messages().send(player, "combat.tag-teleport-blocked");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (!tracker.isTagged(player.getUniqueId())) {
            return;
        }
        if (config.get().worldEnabled(player.getWorld().getName())) {
            return;
        }
        tracker.clearNow(player);
        ctx.messages().send(player, "combat.tag-world-disabled");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanish(VanishStateChangeEvent event) {
        if (event.isVanishing() && tracker.isTagged(event.getPlayer().getUniqueId())) {
            tracker.forget(event.getPlayer().getUniqueId());
        }
    }

    // ---- end of combat ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        if (tracker.isTagged(id)) {
            tracker.forget(id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean combatLog = tracker.isTagged(id) && config.get().logout.enabled
                && !player.hasPermission(CombatTracker.BYPASS_PERMISSION);
        tracker.forgetNow(id);
        if (combatLog) {
            punish(player);
        }
    }

    private void punish(Player player) {
        CombatConfig.Logout logout = config.get().logout;
        Location location = player.getLocation().clone();
        int stacks = 0;
        switch (logout.mode()) {
            case NONE -> {
            }
            case DROP -> stacks = dropInventory(player, location, logout.dropExperience);
            case KILL -> {
                if (!player.isDead() && player.getHealth() > 0) {
                    player.setHealth(0.0);
                } else {
                    stacks = dropInventory(player, location, logout.dropExperience);
                }
            }
        }
        if (logout.broadcast) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                ctx.messages().send(online, "combat.logout-broadcast", Msg.unparsed("player", player.getName()));
            }
            ctx.messages().send(Bukkit.getConsoleSender(), "combat.logout-broadcast",
                    Msg.unparsed("player", player.getName()));
        }
        ctx.logger().info("Combat log by {} in {} at {}, {}, {} ({} carried stack(s) dropped, mode {})",
                player.getName(), location.getWorld() == null ? "?" : location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), stacks, logout.mode());
    }

    /** Drops the carried items (main inventory, armour and off hand) and optionally the experience. */
    private int dropInventory(Player player, Location location, boolean dropExperience) {
        List<ItemStack> carried = new ArrayList<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && !stack.getType().isAir()) {
                carried.add(stack.clone());
            }
        }
        for (ItemStack stack : player.getInventory().getArmorContents()) {
            if (stack != null && !stack.getType().isAir()) {
                carried.add(stack.clone());
            }
        }
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (offHand != null && !offHand.getType().isAir()) {
            carried.add(offHand.clone());
        }
        player.getInventory().clear();
        for (ItemStack stack : carried) {
            player.getWorld().dropItemNaturally(location, stack);
        }
        if (dropExperience) {
            int experience = player.calculateTotalExperiencePoints();
            player.setLevel(0);
            player.setExp(0.0f);
            player.setTotalExperience(0);
            if (experience > 0) {
                player.getWorld().spawn(location, ExperienceOrb.class, orb -> orb.setExperience(experience));
            }
        }
        return carried.size();
    }
}
