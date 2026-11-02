package dev.yanianz.atrium.module.vanish;

import com.destroystokyo.paper.event.player.PlayerPickupExperienceEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Join/quit handling and the gameplay rules for vanished players. */
final class VanishListener implements Listener {
    private final ModuleContext ctx;
    private final VanishManager manager;
    private final ConfigHandle<VanishConfig> config;

    VanishListener(ModuleContext ctx, VanishManager manager, ConfigHandle<VanishConfig> config) {
        this.ctx = ctx;
        this.manager = manager;
        this.config = config;
    }

    // ---- join / quit ----

    /** LOW: after the user data is attached (LOWEST, core) and before other plugins read the state. */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        VanishState stored = ctx.users().cached(player.getUniqueId()).flatMap(manager::state).orElse(null);
        manager.restore(player, stored, event);
    }

    /** Other plugins may set a join message later; a vanished player never gets one. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoinMessage(PlayerJoinEvent event) {
        if (manager.isOnlineVanished(event.getPlayer().getUniqueId())) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuitMessage(PlayerQuitEvent event) {
        if (manager.isOnlineVanished(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
    }

    /** MONITOR: other plugins can still ask {@code isVanished} while handling the quit. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.quit(event.getPlayer());
    }

    // ---- server list ----

    @EventHandler(ignoreCancelled = true)
    public void onPing(PaperServerListPingEvent event) {
        if (!config.get().hideFromServerList) {
            return;
        }
        Set<UUID> hidden = manager.onlineVanished();
        if (hidden.isEmpty()) {
            return;
        }
        event.getListedPlayers().removeIf(info -> hidden.contains(info.id()));
        int online = (int) hidden.stream().filter(uuid -> Bukkit.getPlayer(uuid) != null).count();
        event.setNumPlayers(VisibilityRules.visibleCount(event.getNumPlayers(), online));
    }

    // ---- gameplay ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && blocksPickup(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArrowPickup(PlayerPickupArrowEvent event) {
        if (blocksPickup(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onExperiencePickup(PlayerPickupExperienceEvent event) {
        if (config.get().blockExperiencePickup && blocksPickup(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    private boolean blocksPickup(Player player) {
        return manager.isOnlineVanished(player.getUniqueId()) && !manager.pickupEnabled(player);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (config.get().preventMobTargeting && event.getTarget() instanceof Player player
                && manager.isOnlineVanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPhysical(PlayerInteractEvent event) {
        if (event.getAction() == Action.PHYSICAL && config.get().preventPhysicalInteraction
                && manager.isOnlineVanished(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Every vibration listener block (sculk sensor, calibrated sculk sensor, shrieker), not only the sensor. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVibration(BlockReceiveGameEvent event) {
        if (config.get().preventVibrations && event.getEntity() instanceof Player player
                && manager.isOnlineVanished(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        if (config.get().hideAdvancements && manager.isOnlineVanished(event.getPlayer().getUniqueId())) {
            event.message(null);
        }
    }
}
