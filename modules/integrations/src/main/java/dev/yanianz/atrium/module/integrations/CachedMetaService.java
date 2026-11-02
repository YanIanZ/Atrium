package dev.yanianz.atrium.module.integrations;

import dev.yanianz.atrium.api.meta.MetaService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * Thread-safe cache in front of the selected provider. Reads may happen from chat/async threads; the
 * provider snapshots only immutable strings, never a live Bukkit object.
 */
final class CachedMetaService implements MetaService {
    private static final class Snapshot {
        final MetaService.Meta meta;
        final Map<String, String> values = new ConcurrentHashMap<>();

        Snapshot(MetaSnapshot snapshot) {
            this.meta = snapshot.meta();
            this.values.putAll(snapshot.values());
        }
    }

    private static final Snapshot EMPTY = new Snapshot(MetaSnapshot.EMPTY);

    private final MetaProvider provider;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    CachedMetaService(MetaProvider provider, Logger logger) {
        this.provider = provider;
        this.logger = logger;
    }

    MetaProvider provider() {
        return provider;
    }

    @Override
    public MetaService.Meta meta(UUID player) {
        return player == null ? MetaService.Meta.EMPTY : snapshot(player).meta;
    }

    @Override
    public Optional<String> value(UUID player, String key) {
        if (player == null || key == null || key.isBlank()) {
            return Optional.empty();
        }
        Snapshot snapshot = snapshot(player);
        if ("prefix".equalsIgnoreCase(key)) {
            return Optional.of(snapshot.meta.prefix());
        }
        if ("suffix".equalsIgnoreCase(key)) {
            return Optional.of(snapshot.meta.suffix());
        }
        if ("primary_group".equalsIgnoreCase(key) || "primarygroup".equalsIgnoreCase(key)) {
            return Optional.of(snapshot.meta.primaryGroup());
        }
        String cached = snapshot.values.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            Optional<String> loaded = provider.value(player, key);
            loaded.ifPresent(value -> snapshot.values.put(key, value));
            return loaded;
        } catch (RuntimeException | LinkageError e) {
            logger.warn("Metadata provider '{}' failed to read '{}' for {}; returning empty", provider.id(), key,
                    player, e);
            return Optional.empty();
        }
    }

    /**
     * Recomputes the snapshot for one player (join, module enable). Called on the player's own thread, so
     * a provider that needs the region thread can answer; a {@code null} answer never replaces a snapshot.
     */
    void refresh(UUID player) {
        MetaSnapshot loaded = load(player);
        if (loaded != null) {
            cache.put(player, new Snapshot(loaded));
        }
    }

    /** Invalidates one player, keeping the next read cheap and current. */
    void invalidate(UUID player) {
        cache.remove(player);
    }

    void invalidateAll() {
        cache.clear();
    }

    private Snapshot snapshot(UUID player) {
        Snapshot cached = cache.get(player);
        if (cached != null) {
            return cached;
        }
        MetaSnapshot loaded = load(player);
        if (loaded == null) {
            // The provider cannot be read on this (async) thread. Answer empty for now but do NOT cache it:
            // caching would pin an empty prefix/suffix until the next join or LuckPerms recalculation, and
            // Vault has no invalidation event at all.
            return EMPTY;
        }
        Snapshot fresh = new Snapshot(loaded);
        Snapshot raced = cache.putIfAbsent(player, fresh);
        return raced != null ? raced : fresh;
    }

    /** @return the snapshot, or {@code null} when the provider refuses to answer on this thread. */
    private MetaSnapshot load(UUID player) {
        try {
            return provider.load(player);
        } catch (RuntimeException | LinkageError e) {
            // Chat formatting must never fail because a permissions plugin is temporarily unhappy.
            logger.warn("Metadata provider '{}' failed for {}; using empty values", provider.id(), player, e);
            return MetaSnapshot.EMPTY;
        }
    }
}
