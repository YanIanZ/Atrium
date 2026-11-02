package dev.yanianz.atrium.module.worlds;

import com.infernalsuite.asp.api.AdvancedSlimePaperAPI;
import com.infernalsuite.asp.api.exceptions.CorruptedWorldException;
import com.infernalsuite.asp.api.exceptions.NewerFormatException;
import com.infernalsuite.asp.api.exceptions.UnknownWorldException;
import com.infernalsuite.asp.api.loaders.SlimeLoader;
import com.infernalsuite.asp.api.world.SlimeWorld;
import com.infernalsuite.asp.api.world.SlimeWorldInstance;
import com.infernalsuite.asp.api.world.properties.SlimeProperties;
import com.infernalsuite.asp.api.world.properties.SlimePropertyMap;
import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldResolver.Resolution;
import dev.yanianz.atrium.api.world.WorldResolver.Status;
import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.slf4j.Logger;

/**
 * Advanced Slime Paper worlds from the loader chosen in {@code slime.loader}. The loader listing is I/O, so it
 * is refreshed on a virtual thread and cached; loading reads the world on a virtual thread
 * ({@code readWorld}) and then adds it on the global thread ({@code loadWorld}), as ASP requires. Unloading
 * runs on the global thread and saves unless the world is read-only. Hook class: loaded only when the ASP API
 * exists.
 */
final class SlimeWorldProvider implements WorldProvider {
    static final String API_CLASS = "com.infernalsuite.asp.api.AdvancedSlimePaperAPI";

    private final AdvancedSlimePaperAPI asp;
    private final SlimeLoader loader;
    private final String loaderName;
    private final boolean readOnly;
    private final Scheduler scheduler;
    private final Logger logger;
    private volatile List<String> listed = List.of();
    private final AtomicBoolean refreshing = new AtomicBoolean();

    private SlimeWorldProvider(AdvancedSlimePaperAPI asp, SlimeLoader loader, String loaderName, boolean readOnly,
            Scheduler scheduler, Logger logger) {
        this.asp = asp;
        this.loader = loader;
        this.loaderName = loaderName;
        this.readOnly = readOnly;
        this.scheduler = scheduler;
        this.logger = logger;
    }

    /** Creates the provider, or throws with a reason (unknown loader, ASPaperPlugin missing, connection failed). */
    static SlimeWorldProvider create(WorldsConfig.Slime cfg, Path serverRoot, Scheduler scheduler, Logger logger)
            throws Exception {
        AdvancedSlimePaperAPI asp = AdvancedSlimePaperAPI.instance();
        String name = cfg.loader == null ? "file" : cfg.loader.trim().toLowerCase(Locale.ROOT);
        SlimeLoader loader = switch (name) {
            case "file" -> new SlimeFileLoader(serverRoot.resolve(cfg.filePath == null || cfg.filePath.isBlank()
                    ? "slime_worlds" : cfg.filePath));
            case "mysql" -> {
                requireClass(SlimePluginLoaders.MYSQL_CLASS, name);
                yield SlimePluginLoaders.mysql(cfg.mysql);
            }
            case "mongo", "mongodb" -> {
                requireClass(SlimePluginLoaders.MONGO_CLASS, name);
                yield SlimePluginLoaders.mongo(cfg.mongodb);
            }
            case "api" -> {
                requireClass(SlimePluginLoaders.API_CLASS, name);
                yield SlimePluginLoaders.api(cfg.api);
            }
            default -> throw new IllegalArgumentException("unknown slime.loader '" + cfg.loader
                    + "' (file, mysql, mongodb, api)");
        };
        return new SlimeWorldProvider(asp, loader, name, cfg.readOnly, scheduler, logger);
    }

    private static void requireClass(String className, String loader) {
        try {
            Class.forName(className, false, SlimeWorldProvider.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new IllegalStateException("slime.loader '" + loader + "' needs the ASPaperPlugin (its loaders are missing)");
        }
    }

    String loaderName() {
        return loaderName;
    }

    /** Re-reads the loader's world list on a virtual thread (one refresh at a time). */
    CompletableFuture<Void> refresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return scheduler.supplyIo(loader::listWorlds).handle((names, error) -> {
            refreshing.set(false);
            if (error != null) {
                logger.warn("Listing the slime worlds of loader {} failed", loaderName, error);
            } else if (names != null) {
                listed = names.stream().filter(WorldNames::valid).distinct().toList();
            }
            return null;
        });
    }

    @Override
    public String id() {
        return SLIME;
    }

    @Override
    public String displayName() {
        return "Slime";
    }

    @Override
    public List<KnownWorld> knownWorlds() {
        Map<String, KnownWorld> result = new LinkedHashMap<>();
        for (SlimeWorldInstance instance : loadedInstances()) {
            World world = Bukkit.getWorld(instance.getName());
            result.put(instance.getName().toLowerCase(Locale.ROOT), new KnownWorld(instance.getName(), SLIME, world != null,
                    "", world == null ? null : world.getEnvironment()));
        }
        for (String name : listed) {
            result.putIfAbsent(name.toLowerCase(Locale.ROOT), KnownWorld.of(name, SLIME, Bukkit.getWorld(name) != null));
        }
        return new ArrayList<>(result.values());
    }

    private List<SlimeWorldInstance> loadedInstances() {
        try {
            return List.copyOf(asp.getLoadedWorlds());
        } catch (RuntimeException e) {
            logger.debug("Reading the loaded slime worlds failed", e);
            return List.of();
        }
    }

    @Override
    public CompletableFuture<Resolution> load(String name) {
        World loaded = Bukkit.getWorld(name);
        if (loaded != null) {
            return CompletableFuture.completedFuture(Resolution.loaded(loaded));
        }
        if (!WorldNames.valid(name)) {
            return CompletableFuture.completedFuture(Resolution.failed(name, Status.NOT_FOUND, "invalid world name"));
        }
        return scheduler.supplyIo(() -> asp.readWorld(loader, name, readOnly, new SlimePropertyMap()))
                .thenCompose(slime -> Tasks.nextGlobal(scheduler, () -> addWorld(slime)))
                .handle((world, error) -> {
                    if (error == null && world != null) {
                        return new Resolution(name, world, Status.LOADED_ON_DEMAND, "");
                    }
                    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                    if (cause instanceof UnknownWorldException) {
                        return Resolution.failed(name, Status.NOT_FOUND, "not in slime loader " + loaderName);
                    }
                    if (cause instanceof CorruptedWorldException || cause instanceof NewerFormatException) {
                        logger.warn("Slime world {} cannot be read: {}", name, cause.getMessage());
                    } else {
                        logger.warn("Loading slime world {} failed", name, cause);
                    }
                    return Resolution.failed(name, Status.FAILED, cause == null ? "no world" : String.valueOf(cause.getMessage()));
                });
    }

    private World addWorld(SlimeWorld slime) {
        World existing = Bukkit.getWorld(slime.getName());
        if (existing != null) {
            return existing;
        }
        return asp.loadWorld(slime, true).getBukkitWorld();
    }

    @Override
    public Optional<Location> spawn(World world) {
        try {
            SlimeWorldInstance instance = asp.getLoadedWorld(world.getName());
            if (instance == null) {
                return Optional.empty();
            }
            SlimePropertyMap properties = instance.getPropertyMap();
            int x = properties.getValue(SlimeProperties.SPAWN_X);
            int y = properties.getValue(SlimeProperties.SPAWN_Y);
            int z = properties.getValue(SlimeProperties.SPAWN_Z);
            float yaw = properties.getValue(SlimeProperties.SPAWN_YAW);
            return Optional.of(new Location(world, x + 0.5, y, z + 0.5, yaw, 0));
        } catch (RuntimeException e) {
            logger.debug("Reading the spawn of slime world {} failed", world.getName(), e);
            return Optional.empty();
        }
    }

    @Override
    public boolean canUnload(String world) {
        try {
            return asp.getLoadedWorld(world) != null && Bukkit.getWorld(world) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<UnloadResult> unload(String name) {
        return Tasks.nextGlobal(scheduler, () -> {
            World world = Bukkit.getWorld(name);
            if (world == null || asp.getLoadedWorld(name) == null) {
                return new UnloadResult(UnloadStatus.NOT_LOADED, "");
            }
            if (!world.getPlayers().isEmpty()) {
                return new UnloadResult(UnloadStatus.PLAYERS_PRESENT, "");
            }
            boolean unloaded = Bukkit.unloadWorld(world, !readOnly);
            return unloaded
                    ? new UnloadResult(UnloadStatus.UNLOADED, "")
                    : new UnloadResult(UnloadStatus.FAILED, "the server refused to unload it");
        });
    }
}
