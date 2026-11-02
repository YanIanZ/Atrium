package dev.yanianz.atrium.module.elytra;

import dev.yanianz.atrium.api.player.FlightService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import io.papermc.paper.datacomponent.DataComponentTypes;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.util.TriState;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Sneak boost and double-jump launch. Every handler runs on the player's own thread, so all velocity,
 * gliding and flight changes happen there.
 *
 * <h2>Flight ownership</h2>
 * The launch needs {@code allowFlight} so that a double jump fires {@link PlayerToggleFlightEvent}. The
 * module only "arms" a player whose {@code allowFlight} is currently false, who is in survival/adventure,
 * on the ground, wearing an elytra and has none of the configured fly permissions. The player is then
 * remembered as armed (in memory and with a marker in the player's persistent data, so a crash cannot leave
 * flight on). Only armed players are ever switched back, and a player whose flight was changed by someone
 * else (for example /fly) is released and left alone for the rest of the session.
 */
final class ElytraListener implements Listener {
    static final String PERM_BOOST = "atrium.elytra.boost";
    static final String PERM_LAUNCH = "atrium.elytra.launch";
    /** Flight claim used while the double-jump launch temporarily allows flight. */
    static final String FLIGHT_CLAIM = "elytra:launch";

    /** Movement samples older than this many ticks do not describe the current speed. */
    private static final double SAMPLE_MAX_AGE_TICKS = 10;
    private static final long ARM_CHECK_INTERVAL_NANOS = 250_000_000L;

    private record Sample(UUID world, double x, double y, double z, long nanos, Optional<SpeedMath.Vec> velocity) {}

    private final ModuleContext ctx;
    private final ConfigHandle<ElytraConfig> config;
    private final ElytraStore store;
    /** Present when the player module is installed: flight is then owned through claims. */
    private final FlightService flight;
    private final NamespacedKey armedKey;
    private final ShortCooldowns cooldowns = new ShortCooldowns();
    private final Set<UUID> armed = ConcurrentHashMap.newKeySet();
    /** Players whose flight was changed by someone else while armed; never armed again this session. */
    private final Set<UUID> released = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastArmCheck = new ConcurrentHashMap<>();
    private final Map<UUID, Sample> samples = new ConcurrentHashMap<>();

    ElytraListener(ModuleContext ctx, ConfigHandle<ElytraConfig> config, ElytraStore store) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.flight = ctx.services().get(FlightService.class).orElse(null);
        this.armedKey = new NamespacedKey(ctx.plugin(), "elytra_armed");
    }

    // ---- checks ----

    private boolean worldAllowed(Player player) {
        String world = player.getWorld().getName();
        for (String disabled : config.get().disabledWorlds) {
            if (disabled.equalsIgnoreCase(world)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasFlyPermission(Player player) {
        for (String node : config.get().flyPermissions) {
            if (!node.isBlank() && player.hasPermission(node)) {
                return true;
            }
        }
        return false;
    }

    private static boolean survivalLike(GameMode mode) {
        return mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE;
    }

    static boolean wearingGlider(Player player) {
        ItemStack chest = player.getInventory().getChestplate();
        return chest != null && !chest.isEmpty()
                && (chest.getType() == Material.ELYTRA || chest.hasData(DataComponentTypes.GLIDER));
    }

    /** Standing on something solid (the client-reported on-ground flag is not trusted). */
    private static boolean standing(Player player) {
        Block below = player.getLocation().subtract(0, 0.05, 0).getBlock();
        return below.getType().isSolid();
    }

    private ElytraPrefs prefs(Player player) {
        return ctx.users().user(player).get(store.component);
    }

    // ---- boost ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (!event.isSneaking() || !player.isGliding()) {
            return;
        }
        ElytraConfig.Boost boost = config.get().boost;
        if (!boost.enabled || !player.hasPermission(PERM_BOOST) || !worldAllowed(player) || !prefs(player).boost()) {
            return;
        }
        double max = Math.clamp(boost.maxSpeed, 0.5, 10.0);
        Vector server = player.getVelocity();
        SpeedMath.Vec reported = new SpeedMath.Vec(server.getX(), server.getY(), server.getZ());
        Sample sample = samples.get(player.getUniqueId());
        Optional<SpeedMath.Vec> measured = sample == null
                || (System.nanoTime() - sample.nanos()) / SpeedMath.NANOS_PER_TICK > SAMPLE_MAX_AGE_TICKS
                ? Optional.empty() : sample.velocity();
        double speed = SpeedMath.effectiveSpeed(reported, measured);
        if (speed >= max) {
            ctx.messages().actionBar(player, "elytra.boost-max-speed");
            return;
        }
        long left = cooldowns.tryAcquire(player.getUniqueId(), "boost", System.currentTimeMillis(),
                Math.clamp(boost.cooldownMillis, 0L, 60_000L));
        if (left > 0) {
            ctx.messages().actionBar(player, "elytra.boost-cooldown", Msg.duration("time", Duration.ofMillis(left)));
            return;
        }
        Vector look = player.getLocation().getDirection();
        SpeedMath.Vec base = measured.orElse(reported);
        Optional<SpeedMath.Vec> result = SpeedMath.boost(base, speed, new SpeedMath.Vec(look.getX(), look.getY(), look.getZ()),
                Math.clamp(boost.multiplier, 1.0, 5.0), Math.clamp(boost.minimumSpeed, 0.0, 5.0), max);
        if (result.isEmpty()) {
            return;
        }
        SpeedMath.Vec v = result.get();
        player.setVelocity(new Vector(v.x(), v.y(), v.z()));
        ctx.messages().actionBar(player, "elytra.boosted",
                Msg.unparsed("speed", String.format(Locale.ROOT, "%.1f", v.length() * 20.0)));
    }

    // ---- movement: speed samples and arming ----

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (player.isGliding()) {
            if (armed.contains(id)) {
                disarm(player); // started gliding on their own: the double jump is not needed any more
            }
            Location to = event.getTo();
            long now = System.nanoTime();
            Sample previous = samples.get(id);
            SpeedMath.Vec position = new SpeedMath.Vec(to.getX(), to.getY(), to.getZ());
            Optional<SpeedMath.Vec> velocity = previous == null || !previous.world().equals(to.getWorld().getUID())
                    ? Optional.empty()
                    : SpeedMath.measured(new SpeedMath.Vec(previous.x(), previous.y(), previous.z()), position,
                            now - previous.nanos(), SAMPLE_MAX_AGE_TICKS);
            samples.put(id, new Sample(to.getWorld().getUID(), to.getX(), to.getY(), to.getZ(), now, velocity));
            return;
        }
        if (!samples.isEmpty()) {
            samples.remove(id);
        }
        if (!config.get().launch.enabled && !armed.contains(id)) {
            return;
        }
        long now = System.nanoTime();
        Long last = lastArmCheck.get(id);
        if (last != null && now - last < ARM_CHECK_INTERVAL_NANOS) {
            return;
        }
        lastArmCheck.put(id, now);
        refresh(player);
    }

    /** Arms or disarms the player as the current state requires. Player's thread only. */
    void refresh(Player player) {
        UUID id = player.getUniqueId();
        if (armed.contains(id)) {
            if (flight != null && hasForeignClaim(player)) {
                // /fly (or another feature) took over: drop our claim and leave their flight alone.
                forget(player);
                released.add(id);
                return;
            }
            if (!player.getAllowFlight() || player.isFlying()) {
                // Someone else changed the flight state: it is not ours any more.
                forget(player);
                released.add(id);
                return;
            }
            if (!eligible(player)) {
                disarm(player);
            }
            return;
        }
        if (!released.contains(id) && eligible(player) && standing(player)
                && !player.getAllowFlight() && !player.isFlying()) {
            // Allowed flight would also cancel fall damage; keep it while the player is merely armed.
            player.setFlyingFallDamage(TriState.TRUE);
            if (flight != null) {
                flight.claim(player, FLIGHT_CLAIM);
            } else {
                player.setAllowFlight(true);
            }
            armed.add(id);
            player.getPersistentDataContainer().set(armedKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    private boolean hasForeignClaim(Player player) {
        return flight != null && flight.claims(player).stream().anyMatch(owner -> !FLIGHT_CLAIM.equals(owner));
    }

    private boolean eligible(Player player) {
        return config.get().launch.enabled && survivalLike(player.getGameMode()) && !player.isGliding()
                && player.getVehicle() == null && worldAllowed(player) && !hasFlyPermission(player)
                && player.hasPermission(PERM_LAUNCH) && prefs(player).launch() && wearingGlider(player);
    }

    /** Takes back the flight we granted. Never touches flight another owner still needs. Player's thread only. */
    void disarm(Player player) {
        if (!armed.remove(player.getUniqueId())) {
            return;
        }
        player.getPersistentDataContainer().remove(armedKey);
        player.setFlyingFallDamage(TriState.NOT_SET);
        if (flight != null) {
            flight.release(player, FLIGHT_CLAIM);
        } else if (player.getAllowFlight() && !player.isFlying() && survivalLike(player.getGameMode())
                && !hasFlyPermission(player)) {
            player.setAllowFlight(false);
        }
    }

    /** Drops our ownership without changing flight another owner may still hold. */
    private void forget(Player player) {
        if (armed.remove(player.getUniqueId())) {
            player.getPersistentDataContainer().remove(armedKey);
            player.setFlyingFallDamage(TriState.NOT_SET);
            if (flight != null) {
                flight.release(player, FLIGHT_CLAIM);
            }
        }
    }

    boolean isArmed(UUID player) {
        return armed.contains(player);
    }

    Set<UUID> armedPlayers() {
        return Set.copyOf(armed);
    }

    // ---- launch ----

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!event.isFlying() || !armed.contains(player.getUniqueId())) {
            return; // not our flight (e.g. /fly or creative): leave it completely alone
        }
        if (hasForeignClaim(player)) {
            // Another owner claimed flight after we armed: their flight wins, do not launch.
            forget(player);
            released.add(player.getUniqueId());
            return;
        }
        boolean blockedByOthers = event.isCancelled();
        event.setCancelled(true);
        disarm(player);
        if (blockedByOthers || !eligible(player)) {
            return;
        }
        ElytraConfig.Launch launch = config.get().launch;
        long left = cooldowns.tryAcquire(player.getUniqueId(), "launch", System.currentTimeMillis(),
                Math.clamp(launch.cooldownMillis, 0L, 600_000L));
        if (left > 0) {
            ctx.messages().actionBar(player, "elytra.launch-cooldown", Msg.duration("time", Duration.ofMillis(left)));
            return;
        }
        Vector look = player.getLocation().getDirection();
        double max = Math.clamp(config.get().boost.maxSpeed, 0.5, 10.0);
        SpeedMath.Vec v = SpeedMath.launch(new SpeedMath.Vec(look.getX(), look.getY(), look.getZ()),
                Math.clamp(launch.forwardVelocity, 0.0, 4.0), Math.clamp(launch.upwardVelocity, 0.1, 4.0), max);
        player.setVelocity(new Vector(v.x(), v.y(), v.z()));
        ctx.messages().actionBar(player, "elytra.launched");
        ctx.scheduler().entityLater(player, () -> {
            if (player.isOnline() && !player.isGliding() && wearingGlider(player) && !standing(player)) {
                player.setGliding(true);
            }
        }, null, Math.clamp(launch.glideDelayTicks, 1, 20));
    }

    // ---- lifecycle ----

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // A marker means the server stopped while we had granted flight: take it back.
        if (player.getPersistentDataContainer().has(armedKey, PersistentDataType.BYTE)) {
            player.getPersistentDataContainer().remove(armedKey);
            player.setFlyingFallDamage(TriState.NOT_SET);
            if (flight != null) {
                flight.release(player, FLIGHT_CLAIM);
            } else if (player.getAllowFlight() && survivalLike(player.getGameMode()) && !hasFlyPermission(player)) {
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        disarm(player);
        UUID id = player.getUniqueId();
        released.remove(id);
        lastArmCheck.remove(id);
        samples.remove(id);
        cooldowns.clear(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        disarm(event.getPlayer());
        samples.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        disarm(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        // The game mode change resets flight itself; just drop our ownership.
        forget(event.getPlayer());
        released.remove(event.getPlayer().getUniqueId());
    }

    void clearAll() {
        armed.clear();
        released.clear();
        lastArmCheck.clear();
        samples.clear();
        cooldowns.clearAll();
    }
}
