package dev.yanianz.atrium.api.service;

import java.util.Optional;

/** Type-keyed registry where modules publish the services other modules (and plugins) may use. */
public interface ServiceRegistry {
    <T> void register(Class<T> type, T implementation);

    <T> void unregister(Class<T> type, T implementation);

    <T> Optional<T> get(Class<T> type);

    default <T> T require(Class<T> type) {
        return get(type).orElseThrow(() -> new IllegalStateException("Service not available: " + type.getName()));
    }
}
