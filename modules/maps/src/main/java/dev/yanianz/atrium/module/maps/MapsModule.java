package dev.yanianz.atrium.module.maps;

import com.mojang.brigadier.context.CommandContext;
import dev.yanianz.atrium.api.portals.PortalUseEvent;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.api.vanish.VanishStateChangeEvent;
import dev.yanianz.atrium.api.warps.WarpCreateEvent;
import dev.yanianz.atrium.api.warps.WarpDeleteEvent;
import dev.yanianz.atrium.api.warps.WarpService;
import dev.yanianz.atrium.core.command.Cmds;
import dev.yanianz.atrium.core.command.CommandSpec;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.AtriumModule;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionDefault;

/**
 * Web map integrations for BlueMap, squaremap and dynmap.
 *
 * <p>Markers are built from pure data (warp locations, a configured or cached spawn, portal use
 * events) and then applied on the global region thread, because the map APIs are not all documented
 * as thread-safe. World reads never happen on a region thread while a map API call is in flight.
 *
 * <p>Vanish handling deliberately reads the new state from {@link VanishStateChangeEvent#isVanishing()}
 * instead of asking the service, which still reports the old state while listeners run (the dynmap bug
 * a known pitfall of map integrations). A periodic resync repairs a map plugin that was reloaded during a
 * vanish change, and also re-applies the markers after BlueMap registers its API late.
 */
public final class MapsModule implements AtriumModule, Listener {
    static final String PERM_MAPS = "atrium.maps";
    static final String PERM_REFRESH = "atrium.maps.refresh";

    private static final List<Integration> INTEGRATIONS = List.of(
            new Integration("bluemap", "BlueMap"),
            new Integration("squaremap", "squaremap"),
            new Integration("dynmap", "dynmap"));

    private ModuleContext ctx;
    private ConfigHandle<MapsConfig> config;
    private final Map<String, MapHook> hooks = new ConcurrentHashMap<>();
    private final Map<String, String> inactiveReasons = new ConcurrentHashMap<>();
    private final Map<UUID, String> hiddenPlayers = new ConcurrentHashMap<>();
    private final Map<String, StoredLocation> portals = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile StoredLocation spawn;
    private volatile List<MapMarker> lastMarkers = List.of();
    private volatile long lastRefreshMillis;
    private volatile int lastTotal;
    private volatile boolean shuttingDown;
    private TaskHandle refreshTask = TaskHandle.NOOP;
    private TaskHandle visibilityTask = TaskHandle.NOOP;

    @Override
    public String id() {
        return "maps";
    }

    @Override
    public String description() {
        return "BlueMap, squaremap and dynmap markers, with warp, spawn, portal and vanish bridges (/maps)";
    }

    @Override
    public Set<String> softDependencies() {
        return Set.of("warps", "vanish", "portals");
    }

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        this.ctx = ctx;
        this.shuttingDown = false;
        this.refreshQueued.set(false);
        this.inactiveReasons.clear();
        this.hooks.clear();
        this.config = ctx.config(MapsConfig.class, MapsConfig::new);
        MapsConfig.validate(config.get());
        this.spawn = resolveSpawn();

        registerIntegrations();
        ctx.listen(this);
        ctx.permission(PERM_MAPS, PermissionDefault.OP, "Use /maps status");
        ctx.permission(PERM_REFRESH, PermissionDefault.OP, "Refresh the markers of every map integration");
        registerCommands();
        startTimers();
        scheduleRefresh(1);
        scheduleVisibilityResync(1);
    }

    @Override
    public void reload() {
        MapsConfig.validate(config.get());
        this.spawn = resolveSpawn();
        startTimers();
        scheduleRefresh(1);
        scheduleVisibilityResync(1);
    }

    @Override
    public void disable() {
        shuttingDown = true;
        refreshTask.cancel();
        visibilityTask.cancel();
        restoreVisibility();
        for (MapHook hook : hooks.values()) {
            try {
                hook.shutdown();
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot shut down the {} map integration", hook.displayName(), e);
            }
        }
        hooks.clear();
        hiddenPlayers.clear();
        portals.clear();
        lastMarkers = List.of();
    }

    // ---- integration setup ----

    private void registerIntegrations() {
        for (Integration integration : INTEGRATIONS) {
            if (!ctx.plugin().getServer().getPluginManager().isPluginEnabled(integration.displayName())) {
                inactiveReasons.put(integration.id(), "maps.reason-not-enabled");
                continue;
            }
            try {
                MapHook hook = create(integration.id());
                if (hook != null) {
                    hooks.put(integration.id(), hook);
                }
            } catch (LinkageError e) {
                inactiveReasons.put(integration.id(), "maps.reason-incompatible");
                ctx.logger().warn("{} is enabled but its API is not compatible with this build; integration disabled",
                        integration.displayName(), e);
            } catch (RuntimeException e) {
                inactiveReasons.put(integration.id(), "maps.reason-incompatible");
                ctx.logger().warn("Cannot initialise the {} integration", integration.displayName(), e);
            }
        }
    }

    private MapHook create(String id) {
        return switch (id) {
            case "bluemap" -> new BlueMapHook(ctx, config, this::onMapApiAvailable);
            case "squaremap" -> new SquaremapHook(ctx, config);
            case "dynmap" -> new DynmapHook(ctx, config);
            default -> null;
        };
    }

    /**
     * A map backend finished enabling after us (BlueMap calls this from its API callback). Queue a
     * refresh and a visibility resync instead of writing markers from the plugin's enable thread.
     */
    private void onMapApiAvailable() {
        if (shuttingDown) {
            return;
        }
        scheduleRefresh(1);
        scheduleVisibilityResync(1);
    }

    // ---- markers ----

    private List<MapMarker> buildMarkers() {
        MapsConfig cfg = config.get();
        List<MapMarker> markers = new ArrayList<>();
        WarpService warpService = ctx.services().get(WarpService.class).orElse(null);
        if (cfg.warpMarkers && warpService != null) {
            for (String name : warpService.names()) {
                Optional<StoredLocation> location = warpService.location(name);
                if (location.isEmpty()) {
                    continue;
                }
                StoredLocation l = location.get();
                markers.add(new MapMarker(MapText.markerId("warp", name), MapMarker.Category.WARP,
                        l.world(), l.x(), l.y(), l.z(), 0, name));
            }
        }
        StoredLocation spawnLocation = spawn;
        if (cfg.spawnMarker && spawnLocation != null) {
            markers.add(new MapMarker("spawn", MapMarker.Category.SPAWN, spawnLocation.world(),
                    spawnLocation.x(), spawnLocation.y(), spawnLocation.z(), cfg.spawnAreaRadius,
                    cfg.spawnMarkerLabel));
        }
        if (cfg.portalMarkers) {
            for (Map.Entry<String, StoredLocation> entry : portals.entrySet()) {
                StoredLocation l = entry.getValue();
                markers.add(new MapMarker(MapText.markerId("portal", entry.getKey()), MapMarker.Category.PORTAL,
                        l.world(), l.x(), l.y(), l.z(), cfg.portalMarkerRadius, entry.getKey()));
            }
        }
        return markers;
    }

    private void refreshNow() {
        if (shuttingDown) {
            return;
        }
        List<MapMarker> markers;
        try {
            markers = buildMarkers();
        } catch (RuntimeException e) {
            ctx.logger().error("Building the map marker set failed", e);
            return;
        }
        int total = 0;
        for (MapHook hook : hooks.values()) {
            try {
                hook.apply(markers);
                total += hook.markerCount();
            } catch (RuntimeException e) {
                ctx.logger().error("Map integration {} failed to apply markers", hook.displayName(), e);
            }
        }
        lastMarkers = List.copyOf(markers);
        lastTotal = total;
        lastRefreshMillis = System.currentTimeMillis();
        if (ctx.logger().isDebugEnabled()) {
            ctx.logger().debug("Applied {} map markers from {} sources across {} integrations",
                    total, markers.size(), hooks.size());
        }
    }

    private void scheduleRefresh(long delayTicks) {
        if (shuttingDown || !refreshQueued.compareAndSet(false, true)) {
            return;
        }
        ctx.track(ctx.scheduler().globalLater(() -> {
            refreshQueued.set(false);
            refreshNow();
        }, Math.max(1, delayTicks)));
    }

    // ---- vanish ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVanishChange(VanishStateChangeEvent event) {
        if (!config.get().hideVanished) {
            return;
        }
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        boolean hiding = event.isVanishing();
        ctx.track(ctx.scheduler().global(() -> applyVisibility(uuid, name, hiding)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        // Give dynmap/squaremap a few ticks to register the joining player before touching their state.
        ctx.track(ctx.scheduler().globalLater(() -> {
            if (shuttingDown) {
                return;
            }
            boolean vanished = config.get().hideVanished
                    && ctx.services().get(VanishService.class)
                            .map(service -> service.isVanished(uuid))
                            .orElse(false);
            if (vanished) {
                applyVisibility(uuid, name, true);
            } else if (hiddenPlayers.containsKey(uuid)) {
                applyVisibility(uuid, name, false);
            }
        }, 5));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        String name = event.getPlayer().getName();
        if (!hiddenPlayers.containsKey(uuid)) {
            return;
        }
        // Clear the visibility assertion; a failure keeps the entry so the next resync/join retries.
        ctx.track(ctx.scheduler().global(() -> applyVisibility(uuid, name, false)));
    }

    private void applyVisibility(UUID uuid, String name, boolean hide) {
        if (shuttingDown) {
            return;
        }
        boolean failed = false;
        for (MapHook hook : hooks.values()) {
            if (!hook.supportsPlayerVisibility()) {
                continue;
            }
            try {
                if (hide) {
                    hook.hidePlayer(uuid, name);
                } else {
                    hook.showPlayer(uuid, name);
                }
            } catch (RuntimeException e) {
                failed = true;
                ctx.logger().warn("Could not {} player {} on {}", hide ? "hide" : "show", name,
                        hook.displayName(), e);
            }
        }
        if (hide) {
            hiddenPlayers.put(uuid, name);
        } else if (!failed) {
            // Keep the entry when a backend failed, so the periodic resync retries the restore.
            hiddenPlayers.remove(uuid);
        }
    }

    private void scheduleVisibilityResync(long delayTicks) {
        ctx.track(ctx.scheduler().globalLater(() -> {
            if (!shuttingDown) {
                resyncVisibility();
            }
        }, Math.max(1, delayTicks)));
    }

    /** Reconciles our hidden set with the live vanish state; also repairs a restarted map plugin. */
    private void resyncVisibility() {
        if (shuttingDown) {
            return;
        }
        VanishService current = ctx.services().get(VanishService.class).orElse(null);
        if (!config.get().hideVanished || current == null) {
            restoreVisibility();
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            boolean shouldHide = current.isVanished(uuid);
            boolean isHidden = hiddenPlayers.containsKey(uuid);
            if (shouldHide) {
                // Re-assert even when we already think the player is hidden: a map plugin may have
                // restarted since the last change, and both backends treat this call as idempotent.
                applyVisibility(uuid, player.getName(), true);
            } else if (isHidden) {
                applyVisibility(uuid, player.getName(), false);
            }
        }
        for (Map.Entry<UUID, String> entry : List.copyOf(hiddenPlayers.entrySet())) {
            if (Bukkit.getPlayer(entry.getKey()) == null) {
                // The player is gone; clear the assertion so a later join starts from the real state.
                applyVisibility(entry.getKey(), entry.getValue(), false);
            }
        }
    }

    private void restoreVisibility() {
        if (hiddenPlayers.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, String> entry : List.copyOf(hiddenPlayers.entrySet())) {
            boolean failed = false;
            for (MapHook hook : hooks.values()) {
                if (!hook.supportsPlayerVisibility()) {
                    continue;
                }
                try {
                    hook.showPlayer(entry.getKey(), entry.getValue());
                } catch (RuntimeException e) {
                    failed = true;
                    ctx.logger().warn("Could not restore the {} visibility of {}", hook.displayName(),
                            entry.getValue(), e);
                }
            }
            if (!failed) {
                hiddenPlayers.remove(entry.getKey());
            }
        }
    }

    // ---- portals ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortalUse(PortalUseEvent event) {
        MapsConfig cfg = config.get();
        if (!cfg.portalMarkers) {
            return;
        }
        String name = event.getPortal();
        if (portals.size() >= cfg.portalMarkerMax && !portals.containsKey(name)) {
            return;
        }
        portals.put(name, StoredLocation.of(event.getPlayer().getLocation()));
        scheduleRefresh(20);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWarpCreate(WarpCreateEvent event) {
        // The event fires before the storage write; one second later the warp is in WarpService.
        scheduleRefresh(20);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWarpDelete(WarpDeleteEvent event) {
        scheduleRefresh(20);
    }

    // ---- spawn ----

    private StoredLocation resolveSpawn() {
        MapsConfig cfg = config.get();
        if (cfg.spawnLocation != null && !cfg.spawnLocation.isBlank()) {
            Optional<StoredLocation> decoded = StoredLocation.decode(cfg.spawnLocation);
            if (decoded.isEmpty()) {
                ctx.logger().warn("maps.spawnLocation '{}' is invalid; use world;x;y;z;yaw;pitch", cfg.spawnLocation);
                return null;
            }
            return decoded.get();
        }
        World world = cfg.spawnWorld != null && !cfg.spawnWorld.isBlank()
                ? Bukkit.getWorld(cfg.spawnWorld)
                : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst());
        if (world == null) {
            if (cfg.spawnMarker) {
                ctx.logger().warn("Cannot publish the spawn marker: world '{}' is not loaded",
                        cfg.spawnWorld == null || cfg.spawnWorld.isBlank() ? "<first>" : cfg.spawnWorld);
            }
            return null;
        }
        return StoredLocation.of(world.getSpawnLocation());
    }

    // ---- timers ----

    private void startTimers() {
        refreshTask.cancel();
        visibilityTask.cancel();
        MapsConfig cfg = config.get();
        long refreshPeriod = 20L * cfg.refreshIntervalSeconds;
        refreshTask = ctx.track(ctx.scheduler().globalTimer(this::refreshNow, refreshPeriod, refreshPeriod));
        long visibilityPeriod = 20L * cfg.visibilityResyncSeconds;
        visibilityTask = ctx.track(ctx.scheduler().globalTimer(this::resyncVisibility,
                visibilityPeriod, visibilityPeriod));
    }

    // ---- commands ----

    private void registerCommands() {
        ctx.command(CommandSpec.of(Cmds.literal("maps", PERM_MAPS)
                .executes(this::status)
                .then(Cmds.literal("status").executes(this::status))
                .then(Cmds.literal("refresh")
                        .requires(Cmds.permission(PERM_REFRESH))
                        .executes(this::refresh))
                .build(), "Web map integrations"));
    }

    private int status(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ctx.messages().send(sender, "maps.status-header");
        int total = 0;
        for (Integration integration : INTEGRATIONS) {
            MapHook hook = hooks.get(integration.id());
            if (hook == null) {
                String reasonKey = inactiveReasons.getOrDefault(integration.id(), "maps.reason-not-enabled");
                ctx.messages().send(sender, "maps.status-inactive",
                        Msg.unparsed("name", integration.displayName()),
                        Msg.component("reason", ctx.messages().render(sender, reasonKey)));
                continue;
            }
            total += hook.markerCount();
            switch (hook.state()) {
                case ACTIVE -> {
                    if (hook.supportsPlayerVisibility()) {
                        ctx.messages().send(sender, "maps.status-active-hidden",
                                Msg.unparsed("name", hook.displayName()),
                                Msg.number("markers", hook.markerCount()),
                                Msg.number("hidden", hiddenPlayers.size()));
                    } else {
                        ctx.messages().send(sender, "maps.status-active",
                                Msg.unparsed("name", hook.displayName()),
                                Msg.number("markers", hook.markerCount()));
                    }
                }
                case PENDING -> ctx.messages().send(sender, "maps.status-inactive",
                        Msg.unparsed("name", hook.displayName()),
                        Msg.component("reason", ctx.messages().render(sender, "maps.reason-pending")));
                case ERROR -> ctx.messages().send(sender, "maps.status-error",
                        Msg.unparsed("name", hook.displayName()),
                        Msg.number("markers", hook.markerCount()));
            }
        }
        long worlds = lastMarkers.stream().map(MapMarker::world).distinct().count();
        ctx.messages().send(sender, "maps.status-total",
                Msg.number("markers", total),
                Msg.number("worlds", worlds),
                Msg.number("hidden", hiddenPlayers.size()));
        long refreshed = lastRefreshMillis;
        if (refreshed == 0) {
            ctx.messages().send(sender, "maps.status-never");
        } else {
            long seconds = Math.max(0, (System.currentTimeMillis() - refreshed) / 1000);
            ctx.messages().send(sender, "maps.status-refreshed-ago", Msg.number("seconds", seconds));
        }
        return Cmds.OK;
    }

    private int refresh(CommandContext<CommandSourceStack> c) {
        CommandSender sender = c.getSource().getSender();
        ctx.messages().send(sender, "maps.refresh-started");
        ctx.scheduler().supplyGlobal(() -> {
            refreshNow();
            return lastTotal;
        }).whenComplete((total, error) -> {
            if (error != null) {
                ctx.logger().error("The manual map refresh failed", error);
                reply(sender, "maps.failed");
                return;
            }
            if (total == null || total == 0) {
                reply(sender, "maps.refresh-none");
            } else {
                long worlds = lastMarkers.stream().map(MapMarker::world).distinct().count();
                reply(sender, "maps.refresh-done",
                        Msg.number("markers", total),
                        Msg.number("worlds", worlds));
            }
        });
        return Cmds.OK;
    }

    private void reply(CommandSender sender, String key, TagResolver... tags) {
        if (sender instanceof Player player) {
            ctx.scheduler().entity(player, () -> ctx.messages().send(player, key, tags), null);
        } else {
            ctx.messages().send(sender, key, tags);
        }
    }

    private record Integration(String id, String displayName) {}
}
