package dev.yanianz.atrium.module.worlds;

import dev.yanianz.atrium.api.world.KnownWorld;
import dev.yanianz.atrium.api.world.WorldProvider;
import dev.yanianz.atrium.api.world.WorldProviderRegistry;
import dev.yanianz.atrium.api.world.WorldResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The provider chain: {@link WorldProviderRegistry} plus the {@link WorldResolver} that walks it. The first
 * provider in the configured order that knows a name (or alias) owns it and loads it. Concurrent lookups of one
 * world share one load; every load has a timeout; nothing blocks (callbacks only). A provider that throws is
 * logged and skipped, never fatal for the chain.
 */
final class WorldProviderChain implements WorldResolver, WorldProviderRegistry {
    record Settings(List<String> order, boolean loadOnDemand, Duration timeout) {
        Settings {
            order = order == null ? List.of() : order.stream()
                    .map(id -> id.toLowerCase(Locale.ROOT).trim()).filter(id -> !id.isEmpty()).distinct().toList();
            timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        }
    }

    private final CopyOnWriteArrayList<WorldProvider> registered = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CompletableFuture<Resolution>> inFlight = new ConcurrentHashMap<>();
    private final Supplier<Settings> settings;
    private final Function<String, @Nullable World> loadedWorld;
    private final Logger logger;

    WorldProviderChain(Supplier<Settings> settings, Function<String, @Nullable World> loadedWorld, Logger logger) {
        this.settings = settings;
        this.loadedWorld = loadedWorld;
        this.logger = logger;
    }

    // ---- registry ----

    @Override
    public void register(WorldProvider provider) {
        String id = provider.id().toLowerCase(Locale.ROOT);
        synchronized (registered) {
            for (WorldProvider existing : registered) {
                if (existing.id().equalsIgnoreCase(id) && existing != provider) {
                    throw new IllegalStateException("World provider already registered: " + id);
                }
            }
            if (!registered.contains(provider)) {
                registered.add(provider);
            }
        }
    }

    @Override
    public void unregister(WorldProvider provider) {
        registered.remove(provider);
    }

    /** Configured ids first, in that order; providers missing from the list follow in registration order. */
    @Override
    public List<WorldProvider> providers() {
        List<String> order = settings.get().order();
        List<WorldProvider> result = new ArrayList<>(registered);
        result.sort(Comparator.comparingInt(provider -> {
            int index = order.indexOf(provider.id().toLowerCase(Locale.ROOT));
            return index < 0 ? Integer.MAX_VALUE : index;
        }));
        return List.copyOf(result);
    }

    @Override
    public Optional<WorldProvider> provider(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return registered.stream().filter(provider -> provider.id().equalsIgnoreCase(id)).findFirst();
    }

    @Override
    public Optional<Match> find(String nameOrAlias) {
        if (nameOrAlias == null || nameOrAlias.isEmpty()) {
            return Optional.empty();
        }
        for (WorldProvider provider : providers()) {
            try {
                Optional<String> name = provider.canonicalName(nameOrAlias);
                if (name.isPresent()) {
                    return Optional.of(new Match(provider, name.get()));
                }
            } catch (RuntimeException | LinkageError e) {
                logger.warn("World provider {} failed to look up {}", provider.id(), nameOrAlias, e);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<KnownWorld> worlds() {
        Map<String, KnownWorld> result = new LinkedHashMap<>();
        for (WorldProvider provider : providers()) {
            Collection<KnownWorld> worlds;
            try {
                worlds = provider.knownWorlds();
            } catch (RuntimeException | LinkageError e) {
                logger.warn("World provider {} failed to list its worlds", provider.id(), e);
                continue;
            }
            for (KnownWorld world : worlds) {
                result.putIfAbsent(world.name().toLowerCase(Locale.ROOT), world);
            }
        }
        return List.copyOf(result.values());
    }

    // ---- resolver ----

    @Override
    public CompletableFuture<Resolution> lookup(String worldName) {
        if (worldName == null || worldName.isBlank()) {
            return CompletableFuture.completedFuture(Resolution.failed(String.valueOf(worldName), Status.NOT_FOUND, "no name"));
        }
        Optional<Match> match = find(worldName);
        if (match.isEmpty()) {
            World loaded = loadedWorld(worldName);
            return CompletableFuture.completedFuture(loaded != null
                    ? Resolution.loaded(loaded)
                    : Resolution.failed(worldName, Status.NOT_FOUND, "no world provider knows it"));
        }
        return load(match.get().provider(), match.get().world());
    }

    /** Loads through one given provider (for example AWF for a profile world), sharing in-flight loads. */
    CompletableFuture<Resolution> load(WorldProvider provider, String world) {
        CompletableFuture<Resolution> running = inFlight.get(key(world));
        if (running != null) {
            return running;
        }
        World loaded = loadedWorld(world);
        if (loaded != null) {
            return CompletableFuture.completedFuture(Resolution.loaded(loaded));
        }
        Settings current = settings.get();
        if (!current.loadOnDemand()) {
            return CompletableFuture.completedFuture(Resolution.failed(world, Status.NOT_ALLOWED, "loading on demand is off"));
        }
        if (!WorldNames.valid(world)) {
            return CompletableFuture.completedFuture(Resolution.failed(world, Status.NOT_FOUND, "invalid world name"));
        }
        CompletableFuture<Resolution> created = new CompletableFuture<>();
        CompletableFuture<Resolution> raced = inFlight.putIfAbsent(key(world), created);
        if (raced != null) {
            return raced;
        }
        start(provider, world, current.timeout(), created);
        return created;
    }

    private void start(WorldProvider provider, String world, Duration timeout, CompletableFuture<Resolution> target) {
        CompletableFuture<Resolution> load;
        try {
            load = provider.load(world);
        } catch (RuntimeException | LinkageError e) {
            load = CompletableFuture.failedFuture(e);
        }
        if (load == null) {
            load = CompletableFuture.failedFuture(new IllegalStateException("provider returned no future"));
        }
        // copy(): the timeout must not complete the provider's own future.
        load.copy().orTimeout(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS).whenComplete((result, error) -> {
            Resolution resolution;
            if (error == null && result != null) {
                resolution = result.available() && result.status() == Status.LOADED
                        ? new Resolution(result.worldName(), result.world(), Status.LOADED_ON_DEMAND, result.detail())
                        : result;
                if (resolution.available()) {
                    logger.info("Loaded world {} through {}", world, provider.id());
                }
            } else {
                Throwable cause = unwrap(error);
                if (cause instanceof TimeoutException) {
                    resolution = Resolution.failed(world, Status.TIMEOUT, "no answer within " + timeout.toSeconds() + " s");
                    logger.warn("Loading world {} through {} did not finish within {} s", world, provider.id(), timeout.toSeconds());
                } else {
                    resolution = Resolution.failed(world, Status.FAILED, cause == null ? "no result" : String.valueOf(cause.getMessage()));
                    logger.warn("Loading world {} through {} failed", world, provider.id(), cause);
                }
            }
            inFlight.remove(key(world), target);
            target.complete(resolution);
        });
    }

    private @Nullable World loadedWorld(String name) {
        try {
            return loadedWorld.apply(name);
        } catch (RuntimeException e) {
            logger.warn("Looking up loaded world {} failed", name, e);
            return null;
        }
    }

    private static String key(String world) {
        return world.toLowerCase(Locale.ROOT);
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
