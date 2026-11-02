package dev.yanianz.atrium.module.maps;

import java.util.Collection;
import java.util.UUID;

/**
 * One web-map integration. Every method except {@link #shutdown()} is called on the global region
 * thread (the server main thread on Paper): the map APIs are not all guaranteed to be thread-safe, so no
 * region-owned state is ever touched from a player's region thread.
 */
interface MapHook {
    /** Stable lower-case id used in permissions and config, e.g. {@code bluemap}. */
    String id();

    /** Human-readable plugin name for {@code /maps status}. */
    String displayName();

    State state();

    /** Creates, updates and removes the markers this backend should show. */
    void apply(Collection<MapMarker> markers);

    /** Markers actually placed by the last successful apply. */
    int markerCount();

    /** Whether this backend can hide players from its live player layer. */
    default boolean supportsPlayerVisibility() {
        return false;
    }

    default void hidePlayer(UUID uuid, String name) {}

    default void showPlayer(UUID uuid, String name) {}

    /** Removes our markers/listeners. Called while the module is being disabled. */
    default void shutdown() {}

    enum State {
        /** The plugin API is ready and the last apply succeeded. */
        ACTIVE,
        /** The plugin is loading or its API has not been registered yet. */
        PENDING,
        /** The last apply threw; the next refresh retries. */
        ERROR
    }
}
