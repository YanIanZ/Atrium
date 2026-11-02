package dev.yanianz.atrium.module.integrations;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One isolated metadata backend. Loaded only after its plugin has been detected. */
interface MetaProvider extends AutoCloseable {
    String id();

    String displayName();

    String version();

    /**
     * Reads a snapshot. Returns {@code null} when the backend cannot be read safely on the calling thread
     * (Vault Chat must run on the player's own region thread); the cache answers empty without storing
     * that value, so a later read on the right thread still sees the real metadata.
     */
    @Nullable MetaSnapshot load(UUID player);

    Optional<String> value(UUID player, String key);

    @Override
    default void close() {}
}
