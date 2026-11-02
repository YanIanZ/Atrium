package dev.yanianz.atrium.api.profile;

import java.util.Collection;
import java.util.Optional;

/**
 * Registry of {@link ProfileScopedData} providers, published by Atrium core. Thread-safe. A profile
 * integration (the {@code sourbycraft} module) reads it each time it captures or applies state, so
 * providers may come and go with their modules.
 */
public interface ProfileScopedDataRegistry {
    /** Adds a provider. Fails when another provider already uses the same id. */
    void register(ProfileScopedData data);

    /** Removes exactly this provider (no-op when it is not registered). */
    void unregister(ProfileScopedData data);

    Optional<ProfileScopedData> get(String id);

    /** Every registered provider, sorted by id. */
    Collection<ProfileScopedData> all();
}
