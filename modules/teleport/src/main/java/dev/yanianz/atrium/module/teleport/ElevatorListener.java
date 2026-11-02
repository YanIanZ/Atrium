package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import dev.yanianz.atrium.api.teleport.TeleportService.TeleportResult;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Messages;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Sign elevator: right-click an indicator block (a sign by default) to rise to the next indicator,
 * sneak-right-click to sink. Block reads happen on the clicked block's region thread, the move on the
 * player's own thread.
 */
final class ElevatorListener implements Listener {
    static final String PERM_ELEVATOR = "atrium.elevator";

    private final ModuleContext ctx;
    private final Messages messages;
    private final TeleportServiceImpl service;
    private final ConfigHandle<TeleportConfig> config;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    ElevatorListener(ModuleContext ctx, TeleportServiceImpl service, ConfigHandle<TeleportConfig> config) {
        this.ctx = ctx;
        this.messages = ctx.messages();
        this.service = service;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return;
        }
        TeleportConfig.Elevator cfg = config.get().elevator;
        if (!cfg.enabled) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission(PERM_ELEVATOR) || !BlockPatterns.worldAllowed(cfg.worlds, block.getWorld().getName())) {
            return;
        }
        if (!Elevator.matches(cfg.blocks, block.getType().name())) {
            return;
        }
        if (throttled(player.getUniqueId(), Math.max(0, cfg.cooldownMillis))) {
            return;
        }
        // The clicked block belongs to the elevator; no sign edit, no item use.
        event.setCancelled(true);
        Location clicked = block.getLocation().clone();
        boolean down = player.isSneaking();
        ctx.scheduler().supplyAt(clicked, () -> target(clicked, down, cfg)).thenAccept(found -> {
            if (found.isEmpty()) {
                messages.send(player, down ? "teleport.elevator-none-down" : "teleport.elevator-none-up");
                return;
            }
            int y = found.getAsInt();
            ctx.scheduler().entity(player, () -> ride(player, clicked, y), null);
        });
    }

    /** Runs on the clicked block's region thread. */
    private static OptionalInt target(Location clicked, boolean down, TeleportConfig.Elevator cfg) {
        World world = clicked.getWorld();
        int x = clicked.getBlockX();
        int z = clicked.getBlockZ();
        int from = clicked.getBlockY();
        int minY = world.getMinHeight() + 1;
        int maxY = world.getMaxHeight() - 2;
        int range = Math.max(1, cfg.searchRange);
        // The lowest/highest stop that can be reached from here, without leaving the world.
        int lowBound = Math.max(minY, from - range);
        int highBound = Math.min(maxY, from + range);
        IntPredicate stop = y -> Elevator.matches(cfg.blocks, world.getBlockAt(x, y, z).getType().name());
        return down
                ? Elevator.below(from, Math.max(1, cfg.minGap), lowBound, stop)
                : Elevator.above(from, Math.max(1, cfg.minGap), highBound, stop);
    }

    /** Runs on the player's thread: the destination is on top of the target stop. */
    private void ride(Player player, Location clicked, int targetY) {
        if (!player.isOnline()) {
            return;
        }
        Location now = player.getLocation();
        Location wanted = new Location(clicked.getWorld(), clicked.getX() + 0.5, targetY + 1, clicked.getZ() + 0.5,
                now.getYaw(), now.getPitch());
        service.landing().find(wanted, Elevator.window(config.get().safeLanding)).whenComplete((spot, error) -> {
            if (error != null) {
                ctx.logger().error("Elevator search for {} failed", player.getName(), error);
                messages.send(player, "core.error");
                return;
            }
            if (spot.isEmpty()) {
                messages.send(player, "teleport.elevator-blocked");
                return;
            }
            service.teleport(player, spot.get(), TeleportCause.COMMAND,
                    TeleportOptions.IMMEDIATE.withSafety(TeleportOptions.Safety.SKIP)).thenAccept(result -> {
                        if (result != TeleportResult.SUCCESS) {
                            return;
                        }
                        messages.actionBar(player, "teleport.elevator-ride",
                                dev.yanianz.atrium.core.message.Msg.number("y", targetY + 1));
                    });
        });
    }

    /** True when this click came too soon after the previous ride; records the new use otherwise. */
    private boolean throttled(UUID player, long cooldownMillis) {
        long now = System.currentTimeMillis();
        boolean[] blocked = {false};
        lastUse.compute(player, (uuid, last) -> {
            if (last != null && cooldownMillis > 0 && now - last < cooldownMillis) {
                blocked[0] = true;
                return last;
            }
            return now;
        });
        return blocked[0];
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }
}
