package dev.yanianz.atrium.module.particles;

import com.destroystokyo.paper.ParticleBuilder;
import dev.yanianz.atrium.api.afk.AfkService;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import dev.yanianz.atrium.module.particles.shape.ShapeFrame;
import dev.yanianz.atrium.module.particles.shape.Shapes;
import dev.yanianz.atrium.module.particles.shape.WingPattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.slf4j.Logger;

/**
 * Runs one Folia entity timer per player with an active effect. Every render happens on the player's own
 * region thread, computes the shape there and only sends packets to players whose client tracks the owner.
 */
final class ParticleEngine {
    private final ModuleContext ctx;
    private final ConfigHandle<ParticlesConfig> config;
    private final ParticleStore store;
    private ParticleData data;
    private final Logger logger;
    private final ToIntFunction<Player> slots;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile WingPattern wings;
    private volatile boolean trackingWarned;

    private static final class Session {
        final UUID uuid;
        final int interval;
        final ParticleBudget budget;
        TaskHandle task = TaskHandle.NOOP;
        Location last;
        int tick;
        boolean warned;

        Session(UUID uuid, int interval, int particlesPerSecond) {
            this.uuid = uuid;
            this.interval = interval;
            this.budget = new ParticleBudget(particlesPerSecond, interval);
        }
    }

    ParticleEngine(ModuleContext ctx, ConfigHandle<ParticlesConfig> config, ParticleStore store,
            ParticleData data, ToIntFunction<Player> slots) {
        this.ctx = ctx;
        this.config = config;
        this.store = store;
        this.data = data;
        this.logger = ctx.logger();
        this.slots = slots;
        this.wings = WingPattern.parse(config.get().wingPattern);
    }

    void reload() {
        this.wings = WingPattern.parse(config.get().wingPattern);
        this.data = new ParticleData(config.get(), logger);
        restartAll();
    }

    /**
     * Trims effects above the current slot limit, then starts the timer. Runs on the player's own thread.
     * Called on join: a rank loss while offline must not keep rendering every stored effect.
     */
    void join(Player player) {
        ParticleProfile profile = profileOf(player.getUniqueId());
        int limit = slots.applyAsInt(player);
        if (profile != null && profile.effects().size() > limit) {
            ParticleProfile trimmed = profile.limit(limit);
            try {
                ctx.users().user(player).update(store.component, ignored -> trimmed);
            } catch (IllegalStateException e) {
                logger.warn("Could not trim the particle effects of {}: {}", player.getName(), e.getMessage());
            }
        }
        start(player);
    }

    /** Starts the timer when the player has a visible effect. Must run on the player's thread. */
    void start(Player player) {
        UUID uuid = player.getUniqueId();
        if (sessions.containsKey(uuid)) {
            return;
        }
        ParticleProfile profile = profileOf(uuid);
        if (profile == null || profile.effects().isEmpty() || profile.hidden()) {
            return;
        }
        ParticlesConfig cfg = config.get();
        Session session = new Session(uuid, Math.max(1, cfg.renderIntervalTicks), cfg.maxParticlesPerSecond);
        session.last = player.getLocation().clone();
        sessions.put(uuid, session);
        try {
            session.task = ctx.track(ctx.scheduler().entityTimer(player, () -> tick(player, session),
                    () -> sessions.remove(uuid, session), session.interval, session.interval));
        } catch (RuntimeException e) {
            sessions.remove(uuid, session);
            logger.warn("Could not start the particle timer of {}", player.getName(), e);
        }
    }

    /** Cancels and starts again, picking up the current profile. Must run on the player's thread. */
    void restart(Player player) {
        stop(player.getUniqueId());
        start(player);
    }

    void stop(UUID uuid) {
        Session session = sessions.remove(uuid);
        if (session != null) {
            session.task.cancel();
        }
    }

    void restartAll() {
        for (Player player : List.copyOf(Bukkit.getOnlinePlayers())) {
            try {
                ctx.scheduler().entity(player, () -> restart(player), null);
            } catch (RuntimeException e) {
                logger.warn("Scheduling the particle restart of {} failed", player.getName(), e);
            }
        }
    }

    void shutdown() {
        for (Session session : List.copyOf(sessions.values())) {
            session.task.cancel();
        }
        sessions.clear();
    }

    int activeSessions() {
        return sessions.size();
    }

    private void tick(Player player, Session session) {
        if (!player.isOnline() || sessions.get(session.uuid) != session) {
            stop(session.uuid);
            return;
        }
        ParticleProfile profile = profileOf(session.uuid);
        if (profile == null) {
            return;
        }
        Location location = player.getLocation();
        boolean moving = updateMovement(session, location);
        if (profile.effects().isEmpty() || profile.hidden()) {
            // No idle timer: a new effect (or a visibility toggle) restarts it on the player's thread.
            stop(session.uuid);
            return;
        }
        ParticlesConfig cfg = config.get();
        if (skip(player, cfg)) {
            return;
        }

        List<Player> receivers = receivers(player);
        if (receivers.isEmpty()) {
            return;
        }
        double tps = Bukkit.getServer().getTPS()[0];
        double densityScale = ParticleBudget.throttle(tps, cfg.tpsThreshold, cfg.throttleMultiplier);
        int available = session.budget.refill(session.interval);
        boolean drawn = false;
        int shown = 0;
        int limit = slots.applyAsInt(player);

        for (ParticleEffect effect : profile.effects()) {
            // A rank loss lowers the limit while the timer keeps running: stop at the current slot count.
            if (shown >= limit) {
                break;
            }
            if (effect.style().movingOnly() && !moving) {
                continue;
            }
            if (!player.hasPermission(effect.style().permission())
                    || !player.hasPermission(ParticleNames.permission(effect.particle()))) {
                continue;
            }
            ShapeFrame frame = new ShapeFrame(session.tick, location.getYaw(), moving, effect.radius(),
                    effect.density() * densityScale, effect.speed(), wings);
            double[] offsets = Shapes.offsets(effect.style(), frame);
            int total = Shapes.pointCount(offsets);
            if (total == 0) {
                continue;
            }
            int granted = session.budget.consume(Math.min(total, available));
            available -= granted;
            if (granted <= 0) {
                continue;
            }
            drawn = true;
            shown++;
            spawn(player, location, receivers, effect, offsets, total, granted, session, cfg);
        }
        if (drawn) {
            session.tick++;
        }
    }

    private void spawn(Player owner, Location base, List<Player> receivers, ParticleEffect effect, double[] offsets,
            int total, int granted, Session session, ParticlesConfig cfg) {
        for (int i = 0; i < granted; i++) {
            double x = base.getX() + offsets[i * 3];
            double y = base.getY() + offsets[i * 3 + 1];
            double z = base.getZ() + offsets[i * 3 + 2];
            Color primary = effect.colours().at(i, total, session.tick);
            try {
                ParticleBuilder builder = new ParticleBuilder(effect.particle())
                        .location(base.getWorld(), x, y, z)
                        .count(1)
                        .offset(0.0, 0.0, 0.0)
                        .extra(0.0)
                        .receivers(receivers)
                        .source(owner)
                        .force(false);
                data.apply(builder, effect.particle(), primary, effect.colours().secondary(),
                        (float) cfg.dustSize);
                builder.spawn();
            } catch (RuntimeException e) {
                if (!session.warned) {
                    session.warned = true;
                    logger.warn("Spawning particle {} for {} failed; the effect is skipped until it changes",
                            effect.particle(), owner.getName(), e);
                }
                return;
            }
        }
    }

    private boolean updateMovement(Session session, Location location) {
        Location previous = session.last;
        session.last = location.clone();
        if (previous == null || previous.getWorld() != location.getWorld()) {
            return false;
        }
        double dx = location.getX() - previous.getX();
        double dz = location.getZ() - previous.getZ();
        double threshold = config.get().movementThreshold;
        return Math.hypot(dx, dz) >= threshold;
    }

    private boolean skip(Player player, ParticlesConfig cfg) {
        if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()
                || player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
            return true;
        }
        if (cfg.skipVanished) {
            VanishService vanish = ctx.services().get(VanishService.class).orElse(null);
            if (vanish != null && vanish.isVanished(player.getUniqueId())) {
                return true;
            }
        }
        if (cfg.skipAfk) {
            AfkService afk = ctx.services().get(AfkService.class).orElse(null);
            if (afk != null && afk.isAfk(player.getUniqueId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Players whose client tracks the owner (the server already limits this to the view range) and who did
     * not hide other players' particles. The owner always receives their own effect.
     */
    private List<Player> receivers(Player owner) {
        List<Player> list = new ArrayList<>();
        list.add(owner);
        try {
            for (Player viewer : owner.getTrackedBy()) {
                if (viewer.equals(owner) || !viewer.isOnline()) {
                    continue;
                }
                ParticleProfile profile = profileOf(viewer.getUniqueId());
                if (profile != null && profile.hideOthers()) {
                    continue;
                }
                list.add(viewer);
            }
        } catch (RuntimeException e) {
            if (!trackingWarned) {
                trackingWarned = true;
                logger.warn("Reading the particle viewers of {} failed; only the owner receives particles",
                        owner.getName(), e);
            }
        }
        return list;
    }

    private ParticleProfile profileOf(UUID uuid) {
        var user = ctx.users().cached(uuid);
        if (user.isEmpty()) {
            return null;
        }
        try {
            return user.get().get(store.component);
        } catch (IllegalStateException e) {
            return null;
        }
    }
}
