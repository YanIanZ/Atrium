package dev.yanianz.atrium.module.worlds;

import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldResolver.Resolution;
import dev.yanianz.atrium.api.world.WorldResolver.Status;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.utils.result.Attempt;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.LoadWorldOptions;
import org.mvplugins.multiverse.core.world.options.UnloadWorldOptions;
import org.slf4j.Logger;

/**
 * Multiverse-Core 5 (5.2 or newer) worlds: lists known-but-unloaded worlds, resolves aliases, loads and unloads through
 * Multiverse's world manager (on the global thread: Multiverse is Paper only, where that is the main thread,
 * never a region thread), uses Multiverse spawns and honours {@code multiverse.access.<world>} when Multiverse
 * enforces access. Hook class: loaded only when Multiverse-Core 5 is enabled.
 */
final class MultiverseWorldProvider implements WorldProvider {
    static final String PLUGIN = "Multiverse-Core";
    static final String API_CLASS = "org.mvplugins.multiverse.core.MultiverseCoreApi";
    private static final String ACCESS_PREFIX = "multiverse.access.";

    private final Scheduler scheduler;
    private final BooleanSupplier respectAccess;
    private final Logger logger;

    MultiverseWorldProvider(Scheduler scheduler, BooleanSupplier respectAccess, Logger logger) {
        this.scheduler = scheduler;
        this.respectAccess = respectAccess;
        this.logger = logger;
    }

    private static Optional<WorldManager> manager() {
        if (!MultiverseCoreApi.isLoaded()) {
            return Optional.empty();
        }
        return Optional.of(MultiverseCoreApi.get().getWorldManager());
    }

    @Override
    public String id() {
        return MULTIVERSE;
    }

    @Override
    public String displayName() {
        return "Multiverse";
    }

    @Override
    public List<KnownWorld> knownWorlds() {
        Optional<WorldManager> manager = manager();
        if (manager.isEmpty()) {
            return List.of();
        }
        List<KnownWorld> result = new ArrayList<>();
        for (MultiverseWorld world : allWorlds(manager.get())) {
            if (world.isHidden()) {
                continue;
            }
            String alias = world.getColourlessAlias();
            result.add(new KnownWorld(world.getName(), MULTIVERSE, world.isLoaded(), alias == null ? "" : alias,
                    world.getEnvironment()));
        }
        return result;
    }

    /** Hidden worlds are not listed but can still be reached by name or alias, as in Multiverse. */
    @Override
    public Optional<String> canonicalName(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isEmpty()) {
            return Optional.empty();
        }
        return manager().flatMap(manager -> {
            MultiverseWorld world = manager.getWorldByNameOrAlias(nameOrAlias).getOrNull();
            if (world == null) {
                world = allWorlds(manager).stream()
                        .filter(w -> w.getName().equalsIgnoreCase(nameOrAlias)
                                || nameOrAlias.equalsIgnoreCase(w.getColourlessAlias()))
                        .findFirst().orElse(null);
            }
            return Optional.ofNullable(world).map(MultiverseWorld::getName);
        });
    }

    /**
     * Loaded and unloaded worlds, once each. Not only {@code getWorlds()}: a world unloaded at runtime can be
     * missing there while {@code getUnloadedWorlds()} has it.
     */
    private static List<MultiverseWorld> allWorlds(WorldManager manager) {
        Map<String, MultiverseWorld> worlds = new LinkedHashMap<>();
        for (MultiverseWorld world : List.copyOf(manager.getLoadedWorlds())) {
            worlds.putIfAbsent(world.getName().toLowerCase(Locale.ROOT), world);
        }
        for (MultiverseWorld world : List.copyOf(manager.getUnloadedWorlds())) {
            worlds.putIfAbsent(world.getName().toLowerCase(Locale.ROOT), world);
        }
        for (MultiverseWorld world : List.copyOf(manager.getWorlds())) {
            worlds.putIfAbsent(world.getName().toLowerCase(Locale.ROOT), world);
        }
        return List.copyOf(worlds.values());
    }

    @Override
    public CompletableFuture<Resolution> load(String name) {
        if (manager().isEmpty()) {
            return CompletableFuture.completedFuture(Resolution.failed(name, Status.NOT_FOUND, "Multiverse-Core is not loaded"));
        }
        return Tasks.nextGlobal(scheduler, () -> {
            WorldManager manager = manager().orElse(null);
            if (manager == null) {
                return Resolution.failed(name, Status.NOT_FOUND, "Multiverse-Core is not loaded");
            }
            LoadedMultiverseWorld loaded = manager.getLoadedWorld(name).getOrNull();
            if (loaded != null) {
                World bukkit = loaded.getBukkitWorld().getOrNull();
                if (bukkit != null) {
                    return Resolution.loaded(bukkit);
                }
            }
            MultiverseWorld unloaded = manager.getUnloadedWorld(name).getOrNull();
            if (unloaded == null) {
                return Resolution.failed(name, Status.NOT_FOUND, "not a Multiverse world");
            }
            Attempt<LoadedMultiverseWorld, ?> attempt = manager.loadWorld(LoadWorldOptions.world(unloaded));
            if (attempt.isFailure()) {
                logger.warn("Multiverse could not load world {}: {}", name, attempt.getFailureReason());
                return Resolution.failed(name, Status.FAILED, String.valueOf(attempt.getFailureReason()));
            }
            World bukkit = attempt.get().getBukkitWorld().getOrNull();
            return bukkit == null
                    ? Resolution.failed(name, Status.FAILED, "Multiverse loaded no Bukkit world")
                    : new Resolution(name, bukkit, Status.LOADED_ON_DEMAND, "");
        });
    }

    @Override
    public Optional<Location> spawn(World world) {
        return manager().flatMap(manager -> {
            MultiverseWorld mv = manager.getWorld(world).getOrNull();
            if (mv == null) {
                return Optional.empty();
            }
            Location spawn = mv.getSpawnLocation();
            if (spawn == null) {
                return Optional.empty();
            }
            Location copy = new Location(world, spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getYaw(), spawn.getPitch());
            return Optional.of(copy);
        });
    }

    @Override
    public boolean mayEnter(Player player, String world) {
        if (!respectAccess.getAsBoolean()) {
            return true;
        }
        Optional<WorldManager> manager = manager();
        if (manager.isEmpty() || !manager.get().isWorld(world)) {
            return true;
        }
        if (!MultiverseCoreApi.get().getCoreConfig().getEnforceAccess()) {
            return true;
        }
        return player.hasPermission(ACCESS_PREFIX + world);
    }

    @Override
    public boolean canUnload(String world) {
        return manager().map(manager -> manager.isLoadedWorld(world)).orElse(false);
    }

    @Override
    public CompletableFuture<UnloadResult> unload(String name) {
        if (manager().isEmpty()) {
            return CompletableFuture.completedFuture(new UnloadResult(UnloadStatus.UNSUPPORTED, "Multiverse-Core is not loaded"));
        }
        return Tasks.nextGlobal(scheduler, () -> {
            WorldManager manager = manager().orElse(null);
            LoadedMultiverseWorld loaded = manager == null ? null : manager.getLoadedWorld(name).getOrNull();
            if (loaded == null) {
                return new UnloadResult(UnloadStatus.NOT_LOADED, "");
            }
            World bukkit = loaded.getBukkitWorld().getOrNull();
            if (bukkit != null && !bukkit.getPlayers().isEmpty()) {
                return new UnloadResult(UnloadStatus.PLAYERS_PRESENT, "");
            }
            Attempt<?, ?> attempt = manager.unloadWorld(UnloadWorldOptions.world(loaded).saveBukkitWorld(true));
            if (attempt.isFailure()) {
                return new UnloadResult(UnloadStatus.FAILED, String.valueOf(attempt.getFailureReason()));
            }
            return new UnloadResult(UnloadStatus.UNLOADED, "");
        });
    }
}
