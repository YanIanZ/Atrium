package dev.yanianz.atrium.module.effects;

import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Persistent effects placed by admins.
 *
 * <p>Each effect owns one region timer. The timer stops as soon as nobody is within the view radius and a
 * slow global check starts it again later, so an idle server has no per-tick particle work at all.
 */
final class FixedEffects {
    private final ModuleContext ctx;
    private final FixedEffectStore store;
    private final Map<String, FixedEffect> effects = new ConcurrentHashMap<>();
    private final Map<String, Active> active = new ConcurrentHashMap<>();
    private volatile boolean running;
    private volatile int checkIntervalTicks = 40;
    private volatile double viewRadius = 32.0;
    private TaskHandle checkTask = TaskHandle.NOOP;

    FixedEffects(ModuleContext ctx, FixedEffectStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    /** One running animation timer. Mutable phase, only touched on the owning region thread. */
    private static final class Active {
        private TaskHandle handle = TaskHandle.NOOP;
        private int phase;
    }

    void load() throws Exception {
        List<FixedEffect> loaded = store.loadAll().get(30, java.util.concurrent.TimeUnit.SECONDS);
        for (FixedEffect effect : loaded) {
            effects.put(effect.key(), effect);
        }
    }

    void configure(int intervalTicks, double radius) {
        this.checkIntervalTicks = Math.max(1, Math.min(600, intervalTicks));
        this.viewRadius = Math.max(8.0, Math.min(128.0, radius));
        if (running) {
            restartChecks();
        }
    }

    void start() {
        if (running) {
            return;
        }
        running = true;
        restartChecks();
    }

    void shutdown() {
        running = false;
        checkTask.cancel();
        for (String key : List.copyOf(active.keySet())) {
            stop(key);
        }
    }

    private void restartChecks() {
        checkTask.cancel();
        int period = Math.max(1, checkIntervalTicks);
        checkTask = ctx.track(ctx.scheduler().globalTimer(this::checkAll, period, period));
        // Start immediately instead of waiting up to one interval for the first check.
        ctx.scheduler().global(this::checkAll);
    }

    private void checkAll() {
        if (!running || Bukkit.getOnlinePlayers().isEmpty()) {
            return;
        }
        for (FixedEffect effect : effects.values()) {
            check(effect);
        }
    }

    private void check(FixedEffect effect) {
        if (!running || active.containsKey(effect.key())) {
            return;
        }
        Optional<Location> location = effect.location().toLocation();
        location.ifPresent(centre -> ctx.scheduler().region(centre, () -> {
            if (!running || active.containsKey(effect.key())) {
                return;
            }
            if (!Bukkit.isOwnedByCurrentRegion(centre)) {
                return;
            }
            if (!hasNear(centre)) {
                return;
            }
            start(effect, centre);
        }));
    }

    private void start(FixedEffect effect, Location centre) {
        Active state = new Active();
        Location own = centre.clone();
        state.handle = ctx.track(ctx.scheduler().regionTimer(own, () -> tick(effect, own, state), 1,
                effect.intervalTicks()));
        active.put(effect.key(), state);
    }

    private void tick(FixedEffect effect, Location centre, Active state) {
        if (!running) {
            stop(effect.key());
            return;
        }
        if (!Bukkit.isOwnedByCurrentRegion(centre)) {
            return;
        }
        List<Player> viewers = nearPlayers(centre);
        if (viewers.isEmpty()) {
            stop(effect.key());
            return;
        }
        ParticleData.Check data = effect.data();
        if (data == null) {
            return;
        }
        List<Shapes.Offset> offsets = Shapes.frame(effect.shape(), effect.radius(), effect.height(),
                effect.points(), effect.speed(), state.phase++);
        for (Shapes.Offset offset : offsets) {
            double x = centre.getX() + offset.x();
            double y = centre.getY() + offset.y();
            double z = centre.getZ() + offset.z();
            for (Player viewer : viewers) {
                spawn(viewer, effect, data, x, y, z);
            }
        }
    }

    private void spawn(Player viewer, FixedEffect effect, ParticleData.Check data, double x, double y, double z) {
        if (data.dust() != null) {
            viewer.spawnParticle(effect.particle(), x, y, z, 1, 0.02, 0.02, 0.02, 0.0, data.dust());
        } else if (data.transition() != null) {
            viewer.spawnParticle(effect.particle(), x, y, z, 1, 0.02, 0.02, 0.02, 0.0, data.transition());
        } else if (data.colour() != null) {
            viewer.spawnParticle(effect.particle(), x, y, z, 1, 0.02, 0.02, 0.02, 0.0, data.colour());
        } else {
            viewer.spawnParticle(effect.particle(), x, y, z, 1, 0.02, 0.02, 0.02, 0.0);
        }
    }

    private boolean hasNear(Location centre) {
        if (centre.getWorld() == null) {
            return false;
        }
        // Region-local lookup: never iterate every world player from the effect's region thread.
        List<Nearby.Point> points = new ArrayList<>();
        for (Player player : centre.getNearbyPlayers(viewRadius)) {
            if (!Bukkit.isOwnedByCurrentRegion(player)) {
                continue;
            }
            Location location = player.getLocation();
            points.add(new Nearby.Point(location.getX(), location.getY(), location.getZ()));
        }
        return Nearby.anyWithin(point(centre), viewRadius, points);
    }

    private List<Player> nearPlayers(Location centre) {
        List<Player> out = new ArrayList<>();
        if (centre.getWorld() == null) {
            return out;
        }
        for (Player player : centre.getNearbyPlayers(viewRadius)) {
            if (player.isOnline() && Bukkit.isOwnedByCurrentRegion(player)) {
                out.add(player);
            }
        }
        return out;
    }

    private static Nearby.Point point(Location location) {
        Vector vector = location.toVector();
        return new Nearby.Point(vector.getX(), vector.getY(), vector.getZ());
    }

    private void stop(String key) {
        Active state = active.remove(key);
        if (state != null) {
            state.handle.cancel();
        }
    }

    Collection<FixedEffect> all() {
        return List.copyOf(effects.values());
    }

    Optional<FixedEffect> find(String name) {
        return Optional.ofNullable(effects.get(FixedEffect.key(name)));
    }

    int size() {
        return effects.size();
    }

    /** Stores and starts an effect. The in-memory copy changes only after the write succeeded. */
    CompletableFuture<Void> save(FixedEffect effect) {
        return store.upsert(effect).thenRun(() -> {
            stop(effect.key());
            effects.put(effect.key(), effect);
            check(effect);
        });
    }

    CompletableFuture<Boolean> delete(String name) {
        String key = FixedEffect.key(name);
        if (!effects.containsKey(key)) {
            return CompletableFuture.completedFuture(false);
        }
        return store.delete(key).thenApply(deleted -> {
            if (deleted) {
                stop(key);
                effects.remove(key);
            }
            return deleted;
        });
    }
}
