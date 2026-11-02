package dev.yanianz.atrium.module.sourbycraft;

import dev.yanianz.atrium.api.profile.ActiveProfileService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * {@link ActiveProfileService} with a per-player cache of immutable values. Only tracked (online) players
 * are cached; refreshes for others still answer but leave no entry behind. Callbacks of the profile system
 * have no defined thread, so only the concurrent map is touched there.
 */
final class ActiveProfiles implements ActiveProfileService {
    private final BooleanSupplier enabled;
    private final Function<UUID, CompletableFuture<Optional<ActiveProfile>>> fetch;
    private final Supplier<List<String>> scopes;
    private final Supplier<String> economy;
    private final ConcurrentHashMap<UUID, ActiveProfile> cache = new ConcurrentHashMap<>();
    private final Set<UUID> tracked = ConcurrentHashMap.newKeySet();

    ActiveProfiles(BooleanSupplier enabled, Function<UUID, CompletableFuture<Optional<ActiveProfile>>> fetch,
            Supplier<List<String>> scopes, Supplier<String> economy) {
        this.enabled = enabled;
        this.fetch = fetch;
        this.scopes = scopes;
        this.economy = economy;
    }

    @Override
    public boolean enabled() {
        return enabled.getAsBoolean();
    }

    @Override
    public Optional<ActiveProfile> cached(UUID player) {
        return Optional.ofNullable(cache.get(player));
    }

    @Override
    public CompletableFuture<Optional<ActiveProfile>> refresh(UUID player) {
        CompletableFuture<Optional<ActiveProfile>> future;
        try {
            future = fetch.apply(player);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        return future.whenComplete((profile, error) -> {
            if (error != null || !tracked.contains(player)) {
                return;
            }
            if (profile != null && profile.isPresent()) {
                cache.put(player, profile.get());
            } else {
                cache.remove(player);
            }
        });
    }

    void track(UUID player) {
        tracked.add(player);
    }

    void untrack(UUID player) {
        tracked.remove(player);
        cache.remove(player);
    }

    Set<UUID> trackedPlayers() {
        return Set.copyOf(tracked);
    }

    void clear() {
        tracked.clear();
        cache.clear();
    }

    @Override
    public List<String> scopedData() {
        return scopes.get();
    }

    @Override
    public String economyMode() {
        return economy.get();
    }
}
