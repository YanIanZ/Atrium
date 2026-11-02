package dev.yanianz.atrium.module.seating;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Every way out of a seat goes through here. Cancellable events are handled at MONITOR with
 * {@code ignoreCancelled = true}, so a protection plugin that cancels a break, damage or dismount
 * also cancels Atrium's reaction instead of leaving a half-removed seat behind.
 */
final class SeatingListener implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<SeatingConfig> config;
    private final SeatManager seats;
    private final CrawlManager crawl;

    SeatingListener(ModuleContext ctx, ConfigHandle<SeatingConfig> config, SeatManager seats,
            CrawlManager crawl) {
        this.ctx = ctx;
        this.config = config;
        this.seats = seats;
        this.crawl = crawl;
    }

    // ---- click to sit ----

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        SeatingConfig cfg = config.get();
        if (cfg.interaction == null || !cfg.interaction.enabled) {
            return;
        }
        if (cfg.interaction.notWhileSneaking && player.isSneaking()) {
            return;
        }
        if (cfg.interaction.requireEmptyHand && !player.getInventory().getItemInMainHand().isEmpty()) {
            return;
        }
        if (!player.hasPermission(SeatingModule.PERM_INTERACT)) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        // Only stairs, slabs and (if enabled) carpets can be clicked. This avoids cancelling every
        // other empty-hand interaction in the world.
        SeatRules.Kind kind = SeatManager.kindOf(block.getType());
        if (kind == null) {
            return;
        }
        if (kind == SeatRules.Kind.STAIRS && !cfg.interaction.stairs
                || kind == SeatRules.Kind.SLAB && !cfg.interaction.slabs
                || kind == SeatRules.Kind.CARPET && !cfg.interaction.carpets) {
            return;
        }
        if (seats.sitOnBlock(player, block, player)) {
            event.setCancelled(true);
        }
    }

    // ---- seat cleanup ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            seats.handleDismount(player, event.getDismounted());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSeatRemoved(EntityRemoveEvent event) {
        if (seats.isSeat(event.getEntity())) {
            seats.handleSeatRemoved(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        SeatingConfig cfg = config.get();
        if (cfg.seat != null && cfg.seat.dismountOnDamage) {
            seats.clearPlayer(player, SeatManager.ExitReason.DAMAGED);
        }
        crawl.onDamage(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        // Paper refuses cross-world teleports while a player rides an entity. Dismount at LOWEST so
        // every teleport source (including Atrium's teleportAsync calls) works.
        crawl.stop(player, CrawlRules.StopReason.TELEPORT);
        if (seats.isSeated(player)) {
            seats.stand(player, SeatManager.ExitReason.TELEPORT, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        crawl.stop(player, CrawlRules.StopReason.QUIT);
        seats.clearPlayer(player, SeatManager.ExitReason.QUIT);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        crawl.stop(player, CrawlRules.StopReason.DEATH);
        seats.clearPlayer(player, SeatManager.ExitReason.DEATH);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        crawl.stop(player, CrawlRules.StopReason.WORLD_CHANGE);
        seats.clearPlayer(player, SeatManager.ExitReason.WORLD_CHANGE);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() != GameMode.SPECTATOR) {
            return;
        }
        Player player = event.getPlayer();
        crawl.stop(player, CrawlRules.StopReason.GAMEMODE);
        seats.clearPlayer(player, SeatManager.ExitReason.GAMEMODE);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player player = event.getPlayer();
        crawl.onSneak(player, true);
        SeatingConfig cfg = config.get();
        if (cfg.seat != null && cfg.seat.dismountOnSneak && seats.isSeated(player)) {
            seats.clearPlayer(player, SeatManager.ExitReason.SNEAK);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        crawl.onMove(event.getPlayer(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFlight(PlayerToggleFlightEvent event) {
        if (event.isFlying()) {
            crawl.onFlight(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player player) {
            crawl.onFlight(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player
                && !seats.isSeat(event.getMount())
                && crawl.isCrawling(player)) {
            crawl.stop(player, CrawlRules.StopReason.VEHICLE);
        }
    }

    // ---- block under a seat ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        seats.blockChanged(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        seats.blockChanged(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : List.copyOf(event.blockList())) {
            seats.blockChanged(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : List.copyOf(event.blockList())) {
            seats.blockChanged(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            seats.blockChanged(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            seats.blockChanged(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChangeBlock(EntityChangeBlockEvent event) {
        seats.blockChanged(event.getBlock());
    }

    // ---- orphans ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<Entity> entities = event.getEntities();
        if (entities.isEmpty()) {
            return;
        }
        // Cheap pre-check before asking the manager to inspect every loaded entity.
        boolean anyArmorStand = false;
        for (Entity entity : entities) {
            if (entity instanceof ArmorStand) {
                anyArmorStand = true;
                break;
            }
        }
        if (anyArmorStand) {
            seats.sweepLoaded(entities);
            ctx.logger().debug("Checked {} entities for orphan Atrium seats", entities.size());
        }
    }
}
