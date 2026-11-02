package dev.yanianz.atrium.core.service;

import dev.yanianz.atrium.api.service.ServiceRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class SimpleServiceRegistry implements ServiceRegistry {
    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    @Override
    public <T> void register(Class<T> type, T implementation) {
        Object previous = services.putIfAbsent(type, type.cast(implementation));
        if (previous != null && previous != implementation) {
            throw new IllegalStateException("Service already registered: " + type.getName());
        }
    }

    @Override
    public <T> void unregister(Class<T> type, T implementation) {
        services.remove(type, implementation);
    }

    @Override
    public <T> Optional<T> get(Class<T> type) {
        return Optional.ofNullable(type.cast(services.get(type)));
    }
}
