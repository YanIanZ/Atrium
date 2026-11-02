package dev.yanianz.atrium.module.portals;

import dev.yanianz.atrium.api.actions.ActionResult;
import dev.yanianz.atrium.api.actions.ActionService;
import dev.yanianz.atrium.api.portals.PortalUseEvent;
import dev.yanianz.atrium.api.proxy.ProxyService;
import dev.yanianz.atrium.api.teleport.ExtendedTeleportService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.teleport.TeleportOptions;
import dev.yanianz.atrium.api.teleport.TeleportService;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Portal entry detection and the wand. Every handler runs on the player's own thread (Folia), and only
 * reads the immutable {@link PortalIndex} snapshot. Vanilla nether/end portals are not touched: this
 * listener does not handle {@code PortalCreateEvent} or {@code PlayerPortalEvent} at all.
 */
final class PortalListener implements Listener {
    static final String COOLDOWN_KEY = "portals:use";

    private final PortalsModule module;
    private final ModuleContext ctx;
    private final AtomicBoolean warnedNoActions = new AtomicBoolean();
    private final AtomicBoolean warnedNoWarps = new AtomicBoolean();

    PortalListener(PortalsModule module, ModuleContext ctx) {
        this.module = module;
        this.ctx = ctx;
    }

    // ---- entry detection ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()
                && from.getWorld() == to.getWorld()) {
            return;
        }
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        EntryTracker tracker = module.tracker();
        PortalIndex index = module.registry().index();
        String world = to.getWorld().getName();
        if (!index.hasWorld(world)) {
            if (tracker.isInsideAny(id)) {
                tracker.clear(id);
            }
            return;
        }
        List<Portal> touched = from.getWorld() == to.getWorld()
                ? index.crossedBy(world, from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ())
                : index.containing(world, to.getX(), to.getY(), to.getZ());
        if (touched.isEmpty()) {
            if (tracker.isInsideAny(id)) {
                tracker.clear(id);
            }
            return;
        }
        List<String> touchedKeys = new ArrayList<>(touched.size());
        List<String> insideKeys = new ArrayList<>(touched.size());
        for (Portal portal : touched) {
            touchedKeys.add(portal.key());
            if (portal.area().contains(to.getX(), to.getY(), to.getZ())) {
                insideKeys.add(portal.key());
            }
        }
        List<String> entered = tracker.move(id, touchedKeys, insideKeys);
        if (!entered.isEmpty()) {
            trigger(player, entered.stream().map(k -> module.registry().get(k)).flatMap(Optional::stream).toList());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        Location to = event.getTo();
        List<String> keys = module.registry().index().containing(to.getWorld().getName(), to.getX(), to.getY(), to.getZ())
                .stream().map(Portal::key).toList();
        boolean playerDriven = switch (event.getCause()) {
            case ENDER_PEARL, CONSUMABLE_EFFECT -> true; // chorus fruit is a consumable effect
            default -> false;
        };
        if (!playerDriven || !module.config().triggerOnPearlTeleport) {
            // Command, plugin and portal teleports only update the inside flag; they never trigger.
            module.tracker().set(player.getUniqueId(), keys);
            return;
        }
        List<String> entered = module.tracker().move(player.getUniqueId(), keys, keys);
        if (entered.isEmpty()) {
            return;
        }
        // The teleport is still in progress; check again once the player has arrived.
        ctx.scheduler().entityLater(player, () -> {
            Location now = player.getLocation();
            List<Portal> still = entered.stream().map(k -> module.registry().get(k)).flatMap(Optional::stream)
                    .filter(p -> p.area().world().equals(now.getWorld().getName())
                            && p.area().contains(now.getX(), now.getY(), now.getZ()))
                    .toList();
            if (!still.isEmpty()) {
                trigger(player, still);
            }
        }, null, 1L);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Logging in inside a portal must not trigger it on the first step.
        Player player = event.getPlayer();
        Location l = player.getLocation();
        module.tracker().set(player.getUniqueId(), module.registry().index()
                .containing(l.getWorld().getName(), l.getX(), l.getY(), l.getZ()).stream().map(Portal::key).toList());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        module.tracker().clear(id);
        module.selections().remove(id);
    }

    // ---- use ----

    /** Runs on the player's thread. {@code entered} is in name order. */
    private void trigger(Player player, List<Portal> entered) {
        int seconds = Math.max(0, module.config().reentryCooldownSeconds);
        PortalGate.Decision decision = PortalGate.decide(entered,
                portal -> portal.permission() == null || player.hasPermission(portal.permission()),
                () -> seconds == 0 ? Duration.ZERO
                        : ctx.cooldowns().tryAcquire(ctx.users().user(player), COOLDOWN_KEY, Duration.ofSeconds(seconds)));
        Portal portal = decision.portal();
        switch (decision.outcome()) {
            case NOTHING -> { }
            case DENIED -> {
                if (module.config().notifyNoPermission && portal != null) {
                    ctx.messages().actionBar(player, "portals.no-permission", Msg.unparsed("portal", portal.name()));
                }
            }
            case COOLDOWN -> ctx.messages().actionBar(player, "portals.cooldown", Msg.duration("time", decision.remaining()));
            case USE -> {
                if (portal != null) {
                    use(player, portal);
                }
            }
        }
    }

    private void use(Player player, Portal portal) {
        PortalTarget target = portal.target();
        if (!new PortalUseEvent(player, portal.name(), target.type(), target.value()).callEvent()) {
            return;
        }
        switch (target.type()) {
            case NONE -> runAction(player, portal);
            case LOCATION -> {
                Optional<StoredLocation> stored = target.location();
                Optional<Location> location = stored.flatMap(StoredLocation::toLocation);
                if (location.isEmpty()) {
                    ctx.messages().send(player, "portals.world-not-loaded",
                            Msg.unparsed("world", stored.map(StoredLocation::world).orElse("?")));
                    return;
                }
                teleport(player, portal, location.get(), TeleportService.TeleportCause.PLUGIN);
            }
            case WARP -> {
                Optional<WarpService> warps = ctx.services().get(WarpService.class);
                if (warps.isEmpty()) {
                    if (warnedNoWarps.compareAndSet(false, true)) {
                        ctx.logger().warn("Portal '{}' targets warp '{}' but the warps module is not enabled", portal.name(),
                                target.value());
                    }
                    ctx.messages().send(player, "portals.target-unavailable", Msg.unparsed("portal", portal.name()));
                    return;
                }
                Optional<StoredLocation> stored = warps.get().location(target.value());
                if (stored.isEmpty()) {
                    ctx.logger().warn("Portal '{}' targets warp '{}', which does not exist", portal.name(), target.value());
                    ctx.messages().send(player, "portals.target-unavailable", Msg.unparsed("portal", portal.name()));
                    return;
                }
                Optional<Location> location = stored.get().toLocation();
                if (location.isEmpty()) {
                    ctx.messages().send(player, "portals.world-not-loaded", Msg.unparsed("world", stored.get().world()));
                    return;
                }
                teleport(player, portal, location.get(), TeleportService.TeleportCause.WARP);
            }
            case SERVER -> {
                Optional<ProxyService> proxy = ctx.services().get(ProxyService.class);
                if (proxy.isPresent()) {
                    if (proxy.get().connect(player, target.value())) {
                        ctx.messages().send(player, "portals.server-connecting",
                                Msg.unparsed("server", target.value()));
                        runAction(player, portal);
                    } else {
                        ctx.messages().send(player, "portals.server-unavailable",
                                Msg.unparsed("server", target.value()));
                    }
                    return;
                }
                ctx.logger().info("Portal '{}' would send {} to server '{}': the proxy module is not enabled",
                        portal.name(), player.getName(), target.value());
                ctx.messages().send(player, "portals.server-unavailable", Msg.unparsed("server", target.value()));
                runAction(player, portal);
            }
        }
    }

    private void teleport(Player player, Portal portal, Location destination, TeleportService.TeleportCause cause) {
        PortalsConfig config = module.config();
        Optional<ExtendedTeleportService> service = config.useTeleportService
                ? ctx.services().get(ExtendedTeleportService.class) : Optional.empty();
        CompletableFuture<Boolean> done;
        if (service.isPresent()) {
            TeleportOptions options = TeleportOptions.IMMEDIATE.withSafety(
                    config.safeTeleport ? TeleportOptions.Safety.CHECK : TeleportOptions.Safety.SKIP);
            done = service.get().teleport(player, destination, cause, options)
                    .thenApply(result -> result == TeleportService.TeleportResult.SUCCESS);
        } else {
            done = player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        done.whenComplete((ok, error) -> {
            if (error != null) {
                ctx.logger().error("Portal '{}' could not teleport {}", portal.name(), player.getName(), error);
                return;
            }
            if (!Boolean.TRUE.equals(ok)) {
                return; // cancelled by another plugin or the teleport service (which informs the player)
            }
            ctx.scheduler().entity(player, () -> {
                // Mark the player inside every portal at the destination so it does not send them straight back.
                Location now = player.getLocation();
                module.tracker().set(player.getUniqueId(), module.registry().index()
                        .containing(now.getWorld().getName(), now.getX(), now.getY(), now.getZ())
                        .stream().map(Portal::key).toList());
                ctx.messages().send(player, "portals.teleported", Msg.unparsed("portal", portal.name()));
                runAction(player, portal);
            }, null);
        });
    }

    private void runAction(Player player, Portal portal) {
        String line = portal.action();
        if (line == null) {
            return;
        }
        Optional<ActionService> actions = ctx.services().get(ActionService.class);
        if (actions.isEmpty()) {
            if (warnedNoActions.compareAndSet(false, true)) {
                ctx.logger().warn("Portal '{}' has an action line but the actions module is not enabled", portal.name());
            }
            return;
        }
        actions.get().run(List.of(line), player, Map.of("portal", portal.name())).whenComplete((result, error) -> {
            if (error != null) {
                ctx.logger().error("Action of portal '{}' failed for {}", portal.name(), player.getName(), error);
            } else if (result.status() == ActionResult.Status.INVALID || result.status() == ActionResult.Status.FAILED
                    || result.status() == ActionResult.Status.UNSAFE_VARIABLE) {
                ctx.logger().warn("Action of portal '{}' ended with {} for {}: {}", portal.name(), result.status(),
                        player.getName(), result.detail());
            }
        });
    }

    // ---- wand ----

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || !module.isWand(event.getItem()) || !player.hasPermission(PortalsModule.PERM_ADMIN)) {
            return;
        }
        event.setCancelled(true);
        module.setCorner(player, action == Action.LEFT_CLICK_BLOCK,
                new Selection.Corner(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (module.isWand(player.getInventory().getItemInMainHand()) && player.hasPermission(PortalsModule.PERM_ADMIN)) {
            event.setCancelled(true);
        }
    }
}
