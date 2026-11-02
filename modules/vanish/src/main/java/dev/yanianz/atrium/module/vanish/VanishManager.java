package dev.yanianz.atrium.module.vanish;

import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.api.vanish.VanishStateChangeEvent;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import dev.yanianz.atrium.core.permission.Permissions;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jspecify.annotations.Nullable;

/**
 * Vanish state and visibility. The set of vanished online players is the source of truth for every
 * listener; the user component only persists it.
 *
 * <p>Threading: a state change runs on the vanished player's thread; every {@code hidePlayer}/{@code showPlayer}
 * runs on the viewer's thread. Visibility is re-checked periodically in both directions (hide <em>and</em> show),
 * so a revoked or granted see permission applies without relogging.
 */
final class VanishManager implements VanishService {
    static final String PERM = "atrium.vanish";
    static final String PERM_OTHERS = "atrium.vanish.others";
    static final String PERM_SEE = "atrium.vanish.see";
    static final String PERM_LEVEL = "atrium.vanish.level";
    static final String PERM_SILENT_MODIFY = "atrium.vanish.silentchest.modify";

    enum Result { CHANGED, UNCHANGED, CANCELLED }

    private final ModuleContext ctx;
    private final VanishStore store;
    private final ConfigHandle<VanishConfig> config;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    /** Players whose sleeping-ignored / affects-spawning flag was changed by vanish (restored on reappear). */
    private final Set<UUID> sleepChanged = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spawnChanged = ConcurrentHashMap.newKeySet();

    VanishManager(ModuleContext ctx, VanishStore store, ConfigHandle<VanishConfig> config) {
        this.ctx = ctx;
        this.store = store;
        this.config = config;
    }

    // ---- queries ----

    boolean isOnlineVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    Set<UUID> onlineVanished() {
        return Set.copyOf(vanished);
    }

    @Override
    public boolean isVanished(UUID uuid) {
        if (vanished.contains(uuid)) {
            return true;
        }
        if (Bukkit.getPlayer(uuid) != null) {
            return false;
        }
        return ctx.users().cached(uuid).flatMap(this::state).map(VanishState::vanished).orElse(false);
    }

    @Override
    public boolean canSee(Player viewer, Player target) {
        boolean same = viewer.getUniqueId().equals(target.getUniqueId());
        if (same || !vanished.contains(target.getUniqueId())) {
            return true;
        }
        return VisibilityRules.canSee(false, true, seeLevel(viewer), level(target));
    }

    /** Whether the viewer would see the target while the target is vanished (permission levels only). */
    boolean seesWhenVanished(CommandSender viewer, Player target) {
        if (!(viewer instanceof Player player)) {
            return true;
        }
        return VisibilityRules.canSee(player.getUniqueId().equals(target.getUniqueId()), true, seeLevel(player),
                level(target));
    }

    static int seeLevel(Player viewer) {
        return VisibilityRules.seeLevel(viewer.hasPermission(PERM_SEE), Permissions.limit(viewer, PERM_SEE, 1));
    }

    static int level(Player target) {
        return VisibilityRules.vanishLevel(Permissions.limit(target, PERM_LEVEL, 1));
    }

    Optional<VanishState> state(AtriumUser user) {
        try {
            return Optional.of(user.get(store.component));
        } catch (IllegalStateException notLoaded) {
            return Optional.empty();
        }
    }

    boolean pickupEnabled(Player player) {
        return ctx.users().cached(player.getUniqueId()).flatMap(this::state).map(VanishState::pickup).orElse(false);
    }

    // ---- changes ----

    @Override
    public CompletableFuture<Boolean> setVanished(Player player, boolean value, @Nullable CommandSender actor) {
        return change(player, current -> value, actor).thenApply(result -> result == Result.CHANGED);
    }

    CompletableFuture<Result> toggle(Player player, @Nullable CommandSender actor) {
        return change(player, current -> !current, actor);
    }

    private CompletableFuture<Result> change(Player player, UnaryOperator<Boolean> next, @Nullable CommandSender actor) {
        return ctx.scheduler().supplyOnEntity(player, () -> changeHere(player, next, actor));
    }

    /** Runs on the player's thread. The event carries the new state and is fired before anything changes. */
    private Result changeHere(Player player, UnaryOperator<Boolean> next, @Nullable CommandSender actor) {
        UUID uuid = player.getUniqueId();
        boolean current = vanished.contains(uuid);
        boolean target = next.apply(current);
        if (current == target || !player.isOnline()) {
            return Result.UNCHANGED;
        }
        VanishStateChangeEvent event = new VanishStateChangeEvent(player, target, actor);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled() || !player.isOnline()) {
            return Result.CANCELLED;
        }
        // Record first: if the user data is missing this throws before anything visible changed.
        ctx.users().user(player).update(store.component, s -> s.withVanished(target));
        if (target) {
            vanished.add(uuid);
        } else {
            vanished.remove(uuid);
        }
        applySelf(player, target);
        pushVisibility(player);
        announce(player, target);
        return Result.CHANGED;
    }

    /** Toggles item pickup on the player's thread; returns the new value. */
    CompletableFuture<Boolean> togglePickup(Player player) {
        return ctx.scheduler().supplyOnEntity(player,
                () -> ctx.users().user(player).update(store.component, s -> s.withPickup(!s.pickup())).pickup());
    }

    /** Player-side effects of the state; runs on the player's thread. Only flags changed by vanish are restored. */
    private void applySelf(Player player, boolean isVanished) {
        UUID uuid = player.getUniqueId();
        VanishConfig cfg = config.get();
        if (isVanished) {
            if (cfg.ignoreSleeping && !player.isSleepingIgnored()) {
                player.setSleepingIgnored(true);
                sleepChanged.add(uuid);
            }
            if (cfg.preventMobSpawning && player.getAffectsSpawning()) {
                player.setAffectsSpawning(false);
                spawnChanged.add(uuid);
            }
            if (cfg.preventMobTargeting) {
                clearTargets(player);
            }
        } else {
            if (sleepChanged.remove(uuid)) {
                player.setSleepingIgnored(false);
            }
            if (spawnChanged.remove(uuid)) {
                player.setAffectsSpawning(true);
            }
        }
    }

    private static void clearTargets(Player player) {
        for (Entity entity : player.getNearbyEntities(48, 32, 48)) {
            if (entity instanceof Mob mob && Bukkit.isOwnedByCurrentRegion(mob) && player.equals(mob.getTarget())) {
                mob.setTarget(null);
            }
        }
    }

    private void announce(Player player, boolean nowVanished) {
        VanishConfig cfg = config.get();
        String name = player.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player)) {
                continue;
            }
            String key;
            if (seesWhenVanished(viewer, player)) {
                key = cfg.notifyStaff ? (nowVanished ? "vanish.notify-vanished" : "vanish.notify-reappeared") : null;
            } else {
                key = cfg.fakeMessages ? (nowVanished ? "vanish.fake-quit" : "vanish.fake-join") : null;
            }
            if (key != null) {
                ctx.scheduler().entity(viewer, () -> ctx.messages().send(viewer, key, Msg.unparsed("player", name)), null);
            }
        }
        if (cfg.notifyStaff) {
            ctx.messages().send(Bukkit.getConsoleSender(),
                    nowVanished ? "vanish.notify-vanished" : "vanish.notify-reappeared", Msg.unparsed("player", name));
        }
    }

    // ---- visibility ----

    /** Schedules the visibility of {@code target} for every other online player, each on the viewer's thread. */
    void pushVisibility(Player target) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(target)) {
                ctx.scheduler().entity(viewer, () -> syncPair(viewer, target), null);
            }
        }
    }

    /** Applies the visibility of every online player for one viewer. Must run on the viewer's thread. */
    void syncViewer(Player viewer) {
        if (!viewer.isOnline()) {
            return;
        }
        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!target.equals(viewer)) {
                syncPair(viewer, target);
            }
        }
    }

    /** Must run on the viewer's thread. Hides or shows; both calls are no-ops when already in that state. */
    private void syncPair(Player viewer, Player target) {
        if (!viewer.isOnline() || !target.isOnline()) {
            return;
        }
        if (canSee(viewer, target)) {
            viewer.showPlayer(ctx.plugin(), target);
        } else {
            viewer.hidePlayer(ctx.plugin(), target);
        }
    }

    /** Periodic re-check (global thread): schedules a visibility sync on every viewer's thread. */
    void resyncAll() {
        if (vanished.isEmpty()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            ctx.scheduler().entity(viewer, () -> syncViewer(viewer), null);
        }
    }

    void remindAll() {
        for (UUID uuid : vanished) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                ctx.scheduler().entity(player, () -> {
                    if (vanished.contains(uuid)) {
                        ctx.messages().actionBar(player, "vanish.action-bar");
                    }
                }, null);
            }
        }
    }

    // ---- lifecycle ----

    /**
     * Restores the stored state of a joining (or, after a module reload, already online) player and applies
     * visibility both ways. Runs on the player's thread. Restoring does not fire {@link VanishStateChangeEvent};
     * plugins can check {@link #isVanished} from {@code PlayerJoinEvent} at NORMAL priority or later.
     */
    void restore(Player player, @Nullable VanishState stored, @Nullable PlayerJoinEvent join) {
        UUID uuid = player.getUniqueId();
        boolean wasVanished = stored != null && stored.vanished();
        boolean keep = wasVanished && config.get().keepVanishedOnRejoin && player.hasPermission(PERM);
        if (wasVanished && !keep) {
            ctx.users().cached(uuid).ifPresent(user -> {
                try {
                    user.update(store.component, s -> s.withVanished(false));
                } catch (IllegalStateException notLoaded) {
                    ctx.logger().warn("Could not clear the vanish state of {}: data not loaded", player.getName());
                }
            });
        }
        if (keep) {
            vanished.add(uuid);
            applySelf(player, true);
            if (join != null) {
                join.joinMessage(null);
            }
            ctx.messages().send(player, "vanish.joined-vanished");
        }
        syncViewer(player);
        pushVisibility(player);
    }

    void quit(Player player) {
        UUID uuid = player.getUniqueId();
        vanished.remove(uuid);
        sleepChanged.remove(uuid);
        spawnChanged.remove(uuid);
    }

    /** Module disable: make everyone visible again and restore changed flags. */
    void shutdown() {
        for (UUID uuid : Set.copyOf(vanished)) {
            Player player = Bukkit.getPlayer(uuid);
            vanished.remove(uuid);
            if (player != null) {
                safely(() -> ctx.scheduler().entity(player, () -> applySelf(player, false), null));
            }
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            safely(() -> ctx.scheduler().entity(viewer, () -> {
                for (Player target : Bukkit.getOnlinePlayers()) {
                    if (!target.equals(viewer)) {
                        viewer.showPlayer(ctx.plugin(), target);
                    }
                }
            }, null));
        }
    }

    private void safely(Runnable scheduling) {
        try {
            scheduling.run();
        } catch (RuntimeException e) {
            // The plugin is being disabled (server shutdown): players are disconnected anyway.
            ctx.logger().debug("Skipping vanish cleanup task", e);
        }
    }
}
