package dev.yanianz.atrium.api.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Chain of {@link WorldProvider}s in the order the server owner configured ({@code modules/worlds.yml} →
 * {@code provider-order}). Published by the {@code worlds} module together with a {@link WorldResolver} that
 * walks the chain, so homes, warps, /back and /wtp load known-but-unloaded worlds of any provider.
 *
 * <p>Thread-safe. Providers may come and go with their owners; remove yours on disable.
 */
public interface WorldProviderRegistry {
    /** Adds a provider. Fails when another provider already uses the same id. */
    void register(WorldProvider provider);

    /** Removes exactly this provider (no-op when it is not registered). */
    void unregister(WorldProvider provider);

    /** Registered providers in effective order. */
    List<WorldProvider> providers();

    Optional<WorldProvider> provider(String id);

    /** The first provider (in order) that knows the name or alias, with its canonical world name. */
    Optional<Match> find(String nameOrAlias);

    /** Every known world once (by name, ignoring case); when several providers list it, the first in order wins. */
    List<KnownWorld> worlds();

    record Match(WorldProvider provider, String world) {
        public Match {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(world, "world");
        }
    }
}
