package dev.yanianz.atrium.module.seating;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * {@code /ride}: mount a player or entity, launch the mount, and always leave it on solid ground.
 *
 * <p>Everything runs on the rider's own thread (the command and the dismount event both do), and a dismount
 * that would leave the player inside a block is resolved by moving them up through the free space first.
 * There is no timer: a ride costs nothing while it lasts.
 */
final class RideManager implements Listener {
    private final ModuleContext ctx;
    private final ConfigHandle<SeatingConfig> config;

    RideManager(ModuleContext ctx, ConfigHandle<SeatingConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    /** Mounts the rider onto {@code vehicle}; all checks are already done by the caller's thread. */
    RideRules.Verdict mount(Player rider, Entity vehicle) {
        SeatingConfig.Ride cfg = config.get().ride;
        RideRules.Verdict verdict = RideRules.check(vehicle instanceof Player, cfg.players,
                vehicle.getPassengers().size(), cfg.maxPassengers, suitable(vehicle),
                vehicle.getUniqueId().equals(rider.getUniqueId()));
        if (verdict != RideRules.Verdict.OK) {
            return verdict;
        }
        try {
            if (!vehicle.addPassenger(rider)) {
                return RideRules.Verdict.UNSUITABLE;
            }
        } catch (RuntimeException e) {
            // The vehicle can be owned by a neighbouring region; never let that surface as an internal error.
            ctx.logger().debug("Could not mount {} on {}: {}", rider.getName(), describe(vehicle), e.getMessage());
            return RideRules.Verdict.UNSUITABLE;
        }
        return RideRules.Verdict.OK;
    }

    /**
     * An entity may carry the rider when it is alive and is not already a passenger itself (a riding chain
     * would make the dismount rescue ambiguous).
     */
    static boolean suitable(Entity vehicle) {
        if (!(vehicle instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
            return false;
        }
        return !(vehicle instanceof Player player && player.getGameMode() == org.bukkit.GameMode.SPECTATOR);
    }

    /** Leaves the vehicle and makes sure the player does not end up inside a block. */
    void dismount(Player player) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return;
        }
        player.leaveVehicle();
        player.setFallDistance(0);
        rescue(player);
    }

    private void rescue(Player player) {
        Location location = player.getLocation();
        if (isSafe(location)) {
            return;
        }
        Location target = safeSpot(location);
        if (target == null) {
            return;
        }
        player.teleportAsync(target).exceptionally(error -> {
            if (!(error instanceof Scheduler.EntityRetiredException)) {
                ctx.logger().warn("Could not move {} to a safe spot after riding", player.getName(), error);
            }
            return null;
        });
    }

    /** The first free spot above the player (at most three blocks), or {@code null} when there is none. */
    static @Nullable Location safeSpot(Location location) {
        for (int up = 1; up <= 3; up++) {
            Location candidate = location.clone().add(0, up, 0);
            if (isSafe(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static boolean isSafe(Location location) {
        Block feet = location.getBlock();
        Block head = location.clone().add(0, 1, 0).getBlock();
        Block below = location.clone().subtract(0, 1, 0).getBlock();
        return passable(feet) && passable(head) && !passable(below);
    }

    static boolean passable(Block block) {
        return !block.getType().isSolid();
    }

    /**
     * Launches the mount (or the rider when the server does not expose the mount's velocity) upwards.
     * Clamped by {@link RideRules#launchPower(double)}.
     */
    void launch(Player player, double power) {
        Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return;
        }
        double clamped = RideRules.launchPower(power);
        try {
            Vector velocity = vehicle.getVelocity();
            vehicle.setVelocity(new Vector(velocity.getX(), clamped, velocity.getZ()));
        } catch (RuntimeException e) {
            ctx.logger().debug("Could not launch {} for {}: {}", describe(vehicle), player.getName(), e.getMessage());
            ctx.messages().send(player, "seating.ride-unsuitable");
        }
    }

    /** Nearest entity in front of the player within the configured distance, or {@code null}. */
    @Nullable
    Entity aimed(Player player) {
        double reach = config.get().ride.reach();
        List<Entity> nearby = player.getNearbyEntities(reach, reach, reach);
        Vector direction = player.getEyeLocation().getDirection();
        Location eye = player.getEyeLocation();
        Entity best = null;
        double bestScore = -1;
        for (Entity entity : nearby) {
            if (!(entity instanceof LivingEntity) || entity.getUniqueId().equals(player.getUniqueId())) {
                continue;
            }
            Vector to = entity.getLocation().add(0, entity.getHeight() / 2, 0).toVector().subtract(eye.toVector());
            double distance = to.length();
            if (distance < 0.01 || distance > reach) {
                continue;
            }
            double dot = to.normalize().dot(direction);
            if (dot < 0.75) {
                continue;
            }
            double score = dot - distance / (reach * 10.0);
            if (score > bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }

    void report(Player player, RideRules.Verdict verdict, @Nullable String target) {
        switch (verdict) {
            case OK -> ctx.messages().send(player, "seating.ride-started",
                    Msg.unparsed("target", target == null ? "?" : target));
            case SELF -> ctx.messages().send(player, "seating.ride-self");
            case PLAYERS_DISABLED -> ctx.messages().send(player, "seating.ride-players-disabled");
            case FULL -> ctx.messages().send(player, "seating.ride-full");
            case UNSUITABLE -> ctx.messages().send(player, "seating.ride-unsuitable");
        }
    }

    /** Leaves the vehicle when the rider quits, so no vehicle keeps a passenger that no longer exists. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (player.getVehicle() != null) {
            player.leaveVehicle();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getEntity() instanceof Player player) {
            player.setFallDistance(0);
        }
    }

    /** The label used in messages for a vehicle. */
    static String describe(Entity entity) {
        return entity instanceof Player player ? player.getName() : entity.getType().getKey().getKey();
    }
}
