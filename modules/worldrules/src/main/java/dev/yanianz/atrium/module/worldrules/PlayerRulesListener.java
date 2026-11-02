package dev.yanianz.atrium.module.worldrules;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Player-level rules: keep-inventory/keep-experience, death drops, automatic respawn, compass recovery,
 * the persisted player time and the anti-void rescue.
 */
final class PlayerRulesListener implements Listener {
    static final String PERM_KEEP_INVENTORY = "atrium.worldrules.keepinventory";
    static final String PERM_KEEP_XP = "atrium.worldrules.keepxp";
    static final String PERM_VOID_BYPASS = "atrium.worldrules.voidrescue.bypass";

    private final ModuleContext ctx;
    private final ConfigHandle<WorldRulesConfig> config;
    private final PlayerTimeManager times;
    private final Map<UUID, Location> safeSpots = new ConcurrentHashMap<>();
    private final Map<UUID, Long> rescueCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandle> voidTasks = new ConcurrentHashMap<>();
    private final Set<UUID> compassLost = ConcurrentHashMap.newKeySet();

    PlayerRulesListener(ModuleContext ctx, ConfigHandle<WorldRulesConfig> config, PlayerTimeManager times) {
        this.ctx = ctx;
        this.config = config;
        this.times = times;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        ctx.scheduler().entity(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            times.apply(player);
            startVoidTask(player);
        }, null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        startVoidTask(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        stopVoidTask(id);
        safeSpots.remove(id);
        rescueCooldown.remove(id);
        compassLost.remove(id);
    }

    /** Re-applies the stored time and restarts or stops the void task after a config reload. */
    void reload() {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                times.apply(player);
                startVoidTask(player);
            }, null);
        }
    }

    // ---- death rules ----

    // HIGHEST, not MONITOR: this handler changes the drops and the kept inventory.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        WorldRulesConfig cfg = config.get();
        WorldRule rule = cfg.rule(player.getWorld());

        boolean keepInventory = DeathControl.keepsInventory(rule, cfg.death.keepInventoryPermission,
                player.hasPermission(PERM_KEEP_INVENTORY));
        if (keepInventory) {
            event.setKeepInventory(true);
            event.getDrops().clear();
        } else {
            DeathControl.applyDropPercent(event.getDrops(), event.getItemsToKeep(), rule.deathDropPercent(),
                    ThreadLocalRandom.current());
        }

        boolean keepExperience = DeathControl.keepsExperience(rule, cfg.death.keepExperiencePermission,
                player.hasPermission(PERM_KEEP_XP));
        if (keepExperience) {
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }

        if (cfg.compass.recoverOnDeath && hasCompass(player)) {
            // The recovered compass replaces the dropped one, so the death never duplicates it.
            compassLost.add(player.getUniqueId());
            removeOneCompass(event.getDrops());
        } else {
            compassLost.remove(player.getUniqueId());
        }

        if (cfg.death.autoRespawn) {
            int delay = Math.max(1, Math.min(200, cfg.death.autoRespawnDelayTicks));
            ctx.scheduler().entityLater(player, () -> {
                if (player.isOnline() && player.isDead()) {
                    player.spigot().respawn();
                }
            }, null, delay);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!compassLost.remove(player.getUniqueId())) {
            return;
        }
        ctx.scheduler().entityLater(player, () -> {
            if (!player.isOnline() || hasCompass(player)) {
                return;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(Material.COMPASS));
            for (ItemStack item : leftover.values()) {
                if (item != null) {
                    player.getWorld().dropItemNaturally(player.getLocation(), item);
                }
            }
            ctx.messages().send(player, "worldrules.compass-recovered");
        }, null, 1);
    }

    private static boolean hasCompass(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == Material.COMPASS) {
                return true;
            }
        }
        return false;
    }

    /** Removes a single compass from the death drops so the respawn recovery does not duplicate it. */
    private static void removeOneCompass(java.util.List<ItemStack> drops) {
        for (int index = 0; index < drops.size(); index++) {
            ItemStack item = drops.get(index);
            if (item != null && item.getType() == Material.COMPASS) {
                item.setAmount(item.getAmount() - 1);
                if (item.getAmount() <= 0) {
                    drops.remove(index);
                }
                return;
            }
        }
    }

    // ---- anti-void rescue ----

    private void startVoidTask(Player player) {
        UUID id = player.getUniqueId();
        if (!config.get().voidRescue.enabled) {
            stopVoidTask(id);
            return;
        }
        TaskHandle running = voidTasks.get(id);
        if (running != null && !running.isDone()) {
            return;
        }
        TaskHandle handle = ctx.track(ctx.scheduler().entityTimer(player,
                () -> checkVoid(player), () -> stopVoidTask(id), 10L, 10L));
        voidTasks.put(id, handle);
    }

    private void stopVoidTask(UUID id) {
        TaskHandle handle = voidTasks.remove(id);
        if (handle != null) {
            handle.cancel();
        }
    }

    /** Runs on the player's region thread. */
    private void checkVoid(Player player) {
        WorldRulesConfig cfg = config.get();
        if (!cfg.voidRescue.enabled || !player.isOnline() || player.isDead()
                || player.getGameMode() == GameMode.SPECTATOR
                || player.hasPermission(PERM_VOID_BYPASS)) {
            return;
        }
        World world = player.getWorld();
        if (!cfg.voidRescue.inWorld(world.getName())) {
            return;
        }
        int offset = cfg.voidRescue.triggerOffset();
        Location location = player.getLocation();
        if (!VoidCheck.below(location.getY(), world.getMinHeight(), offset)) {
            if (isStanding(location)) {
                safeSpots.put(player.getUniqueId(), location.clone());
                rescueCooldown.remove(player.getUniqueId());
            }
            return;
        }
        rescue(player, world);
    }

    private static boolean isStanding(Location location) {
        Block below = location.clone().subtract(0, 0.2, 0).getBlock();
        return below.getType().isSolid();
    }

    /** Runs on the player's region thread, after a fall through the world bottom. */
    private void rescue(Player player, World world) {
        WorldRulesConfig cfg = config.get();
        long now = System.currentTimeMillis();
        Long next = rescueCooldown.get(player.getUniqueId());
        if (next != null && now < next) {
            return;
        }
        int cooldown = cfg.voidRescue.cooldownSeconds();
        if (cooldown > 0) {
            rescueCooldown.put(player.getUniqueId(), now + cooldown * 1000L);
        }
        Location target = safeSpot(player, world, cfg.voidRescue.triggerOffset());
        final Location destination = target.clone();
        player.teleportAsync(destination).thenAccept(moved -> {
            if (!Boolean.TRUE.equals(moved)) {
                ctx.logger().warn("Void rescue of {} failed: the teleport was refused", player.getName());
                return;
            }
            ctx.scheduler().entity(player, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.setFallDistance(0);
                player.setNoDamageTicks(Math.max(player.getNoDamageTicks(), 60));
                ctx.messages().send(player, "worldrules.void-rescued",
                        Msg.unparsed("world", destination.getWorld().getName()));
            }, null);
        }).exceptionally(error -> {
            ctx.logger().warn("Void rescue of {} failed", player.getName(), error);
            return null;
        });
    }

    private Location safeSpot(Player player, World world, int offset) {
        WorldRulesConfig.VoidRescue cfg = config.get().voidRescue;
        if (!cfg.toSpawn) {
            Location recorded = safeSpots.get(player.getUniqueId());
            if (recorded != null && recorded.getWorld() != null && recorded.getWorld().equals(world)
                    && VoidCheck.usableSafeSpot(recorded.getY(), world.getMinHeight(), offset)) {
                return recorded;
            }
        }
        return world.getSpawnLocation();
    }
}
