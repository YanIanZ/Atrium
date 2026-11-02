package dev.yanianz.atrium.module.worldrules;

import com.destroystokyo.paper.event.entity.EntityTeleportEndGatewayEvent;
import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.destroystokyo.paper.event.player.PlayerTeleportEndGatewayEvent;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** All gameplay hooks. Everything reacts to an event; nothing runs per tick except an active compass tracker. */
final class WorldRulesListener implements Listener {
    private static final String GAMEMODE_BYPASS = "atrium.worldrules.gamemode.bypass";

    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    private final ArmorEffectManager armor;
    private final CompassManager compass;
    private final Map<UUID, Double> hungerCarry = new ConcurrentHashMap<>();
    private final Map<UUID, Long> pearlReady = new ConcurrentHashMap<>();
    private final Map<UUID, Long> notices = new ConcurrentHashMap<>();
    private final Map<String, Boolean> warnedTargets = new ConcurrentHashMap<>();

    WorldRulesListener(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config,
            ArmorEffectManager armor, CompassManager compass) {
        this.ctx = ctx;
        this.config = config;
        this.armor = armor;
        this.compass = compass;
    }

    private WorldRule rule(Player player) {
        return config.get().rule(player.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        WorldRule rule = rule(player);
        if (rule.disableFallDamage || rule.fallDamageMultiplier() <= 0) {
            event.setCancelled(true);
            return;
        }
        double multiplier = rule.fallDamageMultiplier();
        if (multiplier != 1.0) {
            event.setDamage(WorldRules.fallDamage(event.getDamage(), rule));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID id = player.getUniqueId();
        double carry = hungerCarry.getOrDefault(id, 0.0);
        WorldRules.HungerResult result = WorldRules.hunger(player.getFoodLevel(), event.getFoodLevel(), rule(player), carry);
        if (result.foodLevel() != event.getFoodLevel()) {
            event.setFoodLevel(result.foodLevel());
        }
        if (result.carry() > 0) {
            hungerCarry.put(id, result.carry());
        } else {
            hungerCarry.remove(id);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEnderPearl(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            return;
        }
        Player player = event.getPlayer();
        int seconds = rule(player).enderPearlCooldownSeconds();
        if (seconds <= 0) {
            pearlReady.remove(player.getUniqueId());
            return;
        }
        long now = System.currentTimeMillis();
        Long ready = pearlReady.get(player.getUniqueId());
        if (ready != null && now < ready) {
            event.setCancelled(true);
            long left = Math.max(1, (ready - now + 999) / 1000);
            ctx.messages().send(player, "worldrules.pearl-cooldown", Msg.number("seconds", left));
            return;
        }
        pearlReady.put(player.getUniqueId(), now + seconds * 1000L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player) || !event.isGliding()) {
            return;
        }
        if (rule(player).disableElytra) {
            event.setCancelled(true);
            notice(player, "worldrules.elytra-disabled");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvp(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (rule(victim).disablePvp) {
            event.setCancelled(true);
            notice(victim, "worldrules.pvp-disabled");
        }
    }

    private static Player attacker(Entity damager) {
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Leftovers from a crashed session are removed before the set is evaluated again.
        ctx.scheduler().entity(player, () -> {
            armor.cleanup(player);
            applyWorldEntry(player);
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        hungerCarry.remove(player.getUniqueId());
        ctx.scheduler().entity(player, () -> applyWorldEntry(player), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entityLater(player, () -> applyWorldEntry(player), null, 1);
    }

    /** Runs on the player's thread. */
    private void applyWorldEntry(Player player) {
        if (!player.isOnline()) {
            return;
        }
        WorldRule rule = rule(player);
        GameMode mode = rule.forcedGameMode();
        if (mode != null && player.getGameMode() != mode && !player.hasPermission(GAMEMODE_BYPASS)) {
            player.setGameMode(mode);
        }
        if (rule.disableElytra && player.isGliding()) {
            player.setGliding(false);
        }
        armor.evaluate(player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerGateway(PlayerTeleportEndGatewayEvent event) {
        gateway(event.getFrom(), event, event::setTo);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityGateway(EntityTeleportEndGatewayEvent event) {
        gateway(event.getFrom(), event, event::setTo);
    }

    private void gateway(Location from, Cancellable event, Consumer<Location> setTo) {
        World world = from == null ? null : from.getWorld();
        if (world == null) {
            return;
        }
        WorldRule rule = config.get().rule(world);
        switch (rule.gatewayMode()) {
            case DISABLED -> event.setCancelled(true);
            case FIXED -> {
                WorldRules.GatewayTarget target = WorldRules.parseGatewayTarget(rule.gatewayTarget);
                if (target == null) {
                    warnTarget("invalid gateway-target '" + rule.gatewayTarget + "'");
                    event.setCancelled(true);
                    return;
                }
                World destination = Bukkit.getWorld(target.world());
                if (destination == null) {
                    warnTarget("gateway-target world '" + target.world() + "' is not loaded");
                    event.setCancelled(true);
                    return;
                }
                setTo.accept(new Location(destination, target.x(), target.y(), target.z(), target.yaw(), target.pitch()));
            }
            case NORMAL -> {
                // vanilla
            }
        }
    }

    private void warnTarget(String message) {
        if (warnedTargets.putIfAbsent(message, Boolean.TRUE) == null) {
            ctx.logger().warn("worldrules: {}", message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorChange(PlayerArmorChangeEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entity(player, () -> armor.evaluate(player), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entityLater(player, () -> armor.evaluate(player), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        // Death clears potion effects; drop the ownership record too so the respawn re-applies cleanly.
        armor.remove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        compass.stopFor(id);
        // Runs synchronously on the player's thread: the infinite effect and its ownership record are
        // gone before the player data is saved, so a rejoin can never inherit a permanent effect.
        armor.remove(event.getPlayer());
        hungerCarry.remove(id);
        Long ready = pearlReady.get(id);
        if (ready == null || ready <= System.currentTimeMillis()) {
            pearlReady.remove(id);
        }
        notices.remove(id);
    }

    /** Re-applies the current config to online players, without forcing a game mode on reload. */
    void reload() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                WorldRule rule = rule(player);
                if (rule.disableElytra && player.isGliding()) {
                    player.setGliding(false);
                }
                armor.evaluate(player);
            }, null);
        }
    }

    private void notice(Player player, String key) {
        long now = System.currentTimeMillis();
        Long last = notices.get(player.getUniqueId());
        if (last != null && now - last < 1500L) {
            return;
        }
        notices.put(player.getUniqueId(), now);
        ctx.messages().send(player, key);
    }
}
