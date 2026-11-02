package dev.yanianz.atrium.module.staff;

import dev.yanianz.atrium.core.message.Msg;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jspecify.annotations.Nullable;

/** Spectator sessions with a persisted return point (location and game mode). */
final class SpectateManager {
    private final ModuleContext ctx;
    private final StaffStore store;
    private volatile boolean closing;

    SpectateManager(ModuleContext ctx, StaffStore store) {
        this.ctx = ctx;
        this.store = store;
    }

    void start(Player viewer, Player target) {
        if (viewer.equals(target)) {
            ctx.messages().send(viewer, "staff.spectate-self");
            return;
        }
        SpectateState previous = state(viewer);
        Location here = viewer.getLocation();
        SpectateState next = previous.active()
                ? previous.retarget(target.getName())
                : SpectateState.started(here.getWorld().getName(), here.getX(), here.getY(), here.getZ(),
                        here.getYaw(), here.getPitch(), viewer.getGameMode().name(), target.getName());

        persist(viewer, next).whenComplete((ignored, saveError) -> {
            if (saveError != null) {
                ctx.logger().error("Saving the spectator session of {} failed", viewer.getName(), saveError);
                persist(viewer, previous);
                tell(viewer, "staff.spectate-save-failed");
                return;
            }
            ctx.scheduler().supplyOnEntity(target, target::getLocation)
                    .thenAccept(location -> runOn(viewer, () -> switchTo(viewer, target, location, previous)))
                    .exceptionally(error -> {
                        if (!(error instanceof dev.yanianz.atrium.core.scheduler.Scheduler.EntityRetiredException)) {
                            ctx.logger().warn("Resolving the spectate target {} failed", target.getName(), error);
                        }
                        persist(viewer, previous);
                        tell(viewer, "staff.player-gone", Msg.unparsed("player", target.getName()));
                        return null;
                    });
        });
    }

    /** Runs on the viewer's thread. */
    private void switchTo(Player viewer, Player target, Location location, SpectateState previous) {
        if (!target.isOnline()) {
            persist(viewer, previous);
            ctx.messages().send(viewer, "staff.player-gone", Msg.unparsed("player", target.getName()));
            return;
        }
        viewer.setGameMode(GameMode.SPECTATOR);
        if (viewer.getGameMode() != GameMode.SPECTATOR) {
            persist(viewer, previous);
            ctx.messages().send(viewer, "staff.spectate-failed");
            return;
        }
        viewer.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((moved, error) -> {
            if (error != null || !Boolean.TRUE.equals(moved)) {
                ctx.logger().warn("Spectate teleport of {} failed", viewer.getName(), error);
                tell(viewer, "staff.spectate-failed");
                stop(viewer);
                return;
            }
            runOn(viewer, () -> {
                if (!target.isOnline()) {
                    tell(viewer, "staff.player-gone", Msg.unparsed("player", target.getName()));
                    stop(viewer);
                    return;
                }
                viewer.setSpectatorTarget(target);
                ctx.messages().send(viewer, "staff.spectate-started",
                        Msg.unparsed("player", target.getName()));
            });
        });
    }

    void stop(Player viewer) {
        SpectateState state = state(viewer);
        if (!state.active()) {
            ctx.messages().send(viewer, "staff.spectate-none");
            return;
        }
        runOn(viewer, () -> {
            viewer.setSpectatorTarget(null);
            Location saved = StaffSnapshots.location(state);
            boolean worldMissing = saved == null;
            // Never switch a spectator back to survival where they are (possibly inside blocks).
            Location location = worldMissing ? fallback() : saved;
            if (location == null) {
                finishReturn(viewer, state, true);
                return;
            }
            viewer.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((moved, error) -> {
                        if (error != null || !Boolean.TRUE.equals(moved)) {
                            ctx.logger().warn("Returning {} from spectator mode failed", viewer.getName(), error);
                            tell(viewer, "staff.spectate-failed");
                            return;
                        }
                        runOn(viewer, () -> finishReturn(viewer, state, worldMissing));
                    });
        });
    }

    /** Spawn of the main world: used when the saved world was unloaded or deleted. */
    private static @Nullable Location fallback() {
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
    }

    /** Viewers following {@code quitter} are sent back instead of being left behind as spectators. */
    void handleQuit(Player quitter) {
        String name = quitter.getName();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(quitter)) {
                continue;
            }
            SpectateState state;
            try {
                state = state(viewer);
            } catch (RuntimeException notLoaded) {
                continue;
            }
            if (state.active() && state.target().equalsIgnoreCase(name)) {
                runOn(viewer, () -> {
                    tell(viewer, "staff.player-gone", Msg.unparsed("player", name));
                    stop(viewer);
                });
            }
        }
    }

    /** Module disable: nothing is scheduled any more; sessions stay persisted and end on the next join. */
    void disable() {
        closing = true;
    }

    /** Runs on the viewer's thread. */
    private void finishReturn(Player viewer, SpectateState state, boolean worldMissing) {
        viewer.setGameMode(StaffSnapshots.gameMode(state.gameMode()));
        persist(viewer, SpectateState.EMPTY).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Clearing the spectator session of {} failed; restoring on join",
                        viewer.getName(), error);
            }
            tell(viewer, worldMissing ? "staff.spectate-world-missing" : "staff.spectate-returned");
        });
    }

    /** Restores the pre-spectate state when the player rejoins (or the module starts) with an unfinished session. */
    void restoreOnJoin(Player player) {
        if (closing) {
            return;
        }
        SpectateState state;
        try {
            state = state(player);
        } catch (RuntimeException notLoaded) {
            ctx.logger().warn("Spectator session of {} is not loaded yet; it ends on the next join", player.getName());
            return;
        }
        if (!state.active()) {
            return;
        }
        runOn(player, () -> {
            player.setSpectatorTarget(null);
            Location saved = StaffSnapshots.location(state);
            Location location = saved == null ? fallback() : saved;
            if (location == null) {
                player.setGameMode(StaffSnapshots.gameMode(state.gameMode()));
                clearAndAnnounce(player, "staff.spectate-world-missing");
                return;
            }
            player.teleportAsync(location, PlayerTeleportEvent.TeleportCause.PLUGIN)
                    .whenComplete((moved, error) -> {
                        if (error != null || !Boolean.TRUE.equals(moved)) {
                            ctx.logger().warn("Restoring the spectator session of {} on join failed",
                                    player.getName(), error);
                            tell(player, "staff.spectate-failed");
                            return;
                        }
                        runOn(player, () -> {
                            player.setGameMode(StaffSnapshots.gameMode(state.gameMode()));
                            clearAndAnnounce(player, saved == null
                                    ? "staff.spectate-world-missing" : "staff.spectate-join-restored");
                        });
                    });
        });
    }

    private void clearAndAnnounce(Player player, String key) {
        persist(player, SpectateState.EMPTY).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().error("Clearing the spectator session of {} failed", player.getName(), error);
                return;
            }
            tell(player, key);
        });
    }

    private void tell(Player player, String key, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver... tags) {
        runOn(player, () -> ctx.messages().send(player, key, tags));
    }

    /** Schedules on the player's thread unless the module is being disabled. */
    private void runOn(Player player, Runnable task) {
        if (closing) {
            return;
        }
        try {
            ctx.scheduler().entity(player, task, null);
        } catch (RuntimeException e) {
            ctx.logger().warn("Scheduling a spectate task for {} failed", player.getName(), e);
        }
    }

    private SpectateState state(Player player) {
        return ctx.users().user(player).get(store.spectate);
    }

    private java.util.concurrent.CompletableFuture<Void> persist(Player player, SpectateState state) {
        ctx.users().user(player).update(store.spectate, ignored -> state);
        return ctx.users().saveNow(player.getUniqueId());
    }
}
