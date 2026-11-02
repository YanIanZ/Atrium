package dev.yanianz.atrium.module.sourbycraft;

import dev.yanianz.atrium.api.world.WorldResolver;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * {@link WorldResolver} for SourbyCraft: loaded worlds are returned at once; an AWF world that is not loaded
 * is loaded through the backend (AuroraWorlds) with a timeout. Concurrent lookups of one world share one
 * load. Nothing blocks: the backend's future is followed with callbacks only. The platform calls are behind
 * {@link Backend}, so this class has no SourbyCraft types and is unit tested with fakes.
 */
final class AwfWorldResolver implements WorldResolver {
    /** The platform side. */
    interface Backend {
        /** The world if it is loaded (Bukkit.getWorld). */
        @Nullable World loadedWorld(String name);

        /** Whether the world is managed by AWF (and so can be loaded on demand). */
        boolean managed(String name);

        /** Starts loading; completes with the world once it is fully loaded. */
        CompletableFuture<World> load(String name);

        /** Whether a load failure means "another lifecycle operation is running" (WorldOperationBusyException). */
        boolean busy(Throwable error);
    }

    record Settings(boolean loadOnDemand, Duration timeout, Set<String> deny) {}

    private final Backend backend;
    private final Supplier<Settings> settings;
    private final Logger logger;
    private final ConcurrentHashMap<String, CompletableFuture<Resolution>> inFlight = new ConcurrentHashMap<>();

    AwfWorldResolver(Backend backend, Supplier<Settings> settings, Logger logger) {
        this.backend = backend;
        this.settings = settings;
        this.logger = logger;
    }

    @Override
    public CompletableFuture<Resolution> lookup(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return CompletableFuture.completedFuture(Resolution.failed(String.valueOf(worldName), Status.NOT_FOUND, "no name"));
        }
        // A load in progress first: the world may already be visible before it finished loading.
        CompletableFuture<Resolution> running = inFlight.get(worldName);
        if (running != null) {
            return running;
        }
        World loaded = backend.loadedWorld(worldName);
        if (loaded != null) {
            return CompletableFuture.completedFuture(Resolution.loaded(loaded));
        }
        boolean managed;
        try {
            managed = backend.managed(worldName);
        } catch (RuntimeException e) {
            logger.warn("Checking whether {} is an AWF world failed", worldName, e);
            return CompletableFuture.completedFuture(Resolution.failed(worldName, Status.FAILED, String.valueOf(e.getMessage())));
        }
        if (!managed) {
            return CompletableFuture.completedFuture(Resolution.failed(worldName, Status.NOT_FOUND, "not loaded and not an AWF world"));
        }
        Settings current = settings.get();
        if (!current.loadOnDemand() || current.deny().contains(worldName)) {
            return CompletableFuture.completedFuture(Resolution.failed(worldName, Status.NOT_ALLOWED, "on-demand loading disabled"));
        }
        CompletableFuture<Resolution> created = new CompletableFuture<>();
        CompletableFuture<Resolution> raced = inFlight.putIfAbsent(worldName, created);
        if (raced != null) {
            return raced;
        }
        start(worldName, current.timeout(), created);
        return created;
    }

    private void start(String name, Duration timeout, CompletableFuture<Resolution> target) {
        CompletableFuture<World> load;
        try {
            load = backend.load(name);
        } catch (RuntimeException e) {
            load = CompletableFuture.failedFuture(e);
        }
        // copy(): the timeout must not complete the platform's own future.
        load.copy().orTimeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS).whenComplete((world, error) -> {
            Resolution resolution;
            if (error == null && world != null) {
                resolution = new Resolution(name, world, Status.LOADED_ON_DEMAND, "");
                logger.info("Loaded AWF world {} on demand", name);
            } else {
                Throwable cause = unwrap(error);
                if (cause instanceof TimeoutException) {
                    resolution = Resolution.failed(name, Status.TIMEOUT, "no answer within " + timeout.toSeconds() + " s");
                    logger.warn("Loading AWF world {} did not finish within {} s", name, timeout.toSeconds());
                } else if (cause != null && backend.busy(cause)) {
                    resolution = Resolution.failed(name, Status.BUSY, String.valueOf(cause.getMessage()));
                } else {
                    resolution = Resolution.failed(name, Status.FAILED, cause == null ? "no world" : String.valueOf(cause.getMessage()));
                    logger.warn("Loading AWF world {} on demand failed", name, cause);
                }
            }
            inFlight.remove(name, target);
            target.complete(resolution);
        });
    }

    static @Nullable Throwable unwrap(@Nullable Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    int inFlightCount() {
        return inFlight.size();
    }
}
