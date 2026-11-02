package dev.yanianz.atrium.module.teleport;

import dev.yanianz.atrium.api.teleport.BackHistoryAdminService;
import dev.yanianz.atrium.api.teleport.SpawnAdminService;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.user.AtriumUser;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;

/** Spawn and /back writes for import tools ({@link SpawnAdminService}, {@link BackHistoryAdminService}). */
final class TeleportAdmin implements SpawnAdminService, BackHistoryAdminService {
    private final ModuleContext ctx;
    private final TeleportServiceImpl service;
    private final TeleportStore store;
    private final ConfigHandle<TeleportConfig> config;

    TeleportAdmin(ModuleContext ctx, TeleportServiceImpl service, TeleportStore store, ConfigHandle<TeleportConfig> config) {
        this.ctx = ctx;
        this.service = service;
        this.store = store;
        this.config = config;
    }

    static String spawnId(Kind kind, @Nullable String world) {
        return switch (kind) {
            case DEFAULT -> TeleportStore.DEFAULT_SPAWN;
            case FIRST_JOIN -> TeleportStore.FIRST_JOIN_SPAWN;
            case WORLD -> {
                if (world == null || world.isBlank()) {
                    throw new IllegalArgumentException("a world spawn needs a world name");
                }
                yield TeleportStore.worldSpawnId(world.trim());
            }
        };
    }

    @Override
    public Optional<StoredLocation> spawn(Kind kind, @Nullable String world) {
        return service.spawn(spawnId(kind, world));
    }

    @Override
    public CompletableFuture<Void> setSpawn(Kind kind, @Nullable String world, StoredLocation location) {
        Objects.requireNonNull(location, "location");
        String id;
        try {
            id = spawnId(kind, world);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return service.setSpawn(id, location);
    }

    @Override
    public boolean perWorldSpawns() {
        return config.get().spawn.perWorldSpawns;
    }

    @Override
    public Map<String, StoredLocation> groupSpawns() {
        return SpawnResolver.withPrefix(service.spawnsSnapshot(), TeleportStore.GROUP_PREFIX);
    }

    @Override
    public CompletableFuture<Void> setGroupSpawn(String group, StoredLocation location) {
        Objects.requireNonNull(location, "location");
        if (group == null || group.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("a group spawn needs a group name"));
        }
        return service.setSpawn(TeleportStore.groupSpawnId(group), location);
    }

    @Override
    public Map<String, StoredLocation> worldSpawns() {
        return SpawnResolver.withPrefix(service.spawnsSnapshot(), TeleportStore.WORLD_PREFIX);
    }

    @Override
    public CompletableFuture<Boolean> addBackLocation(UUID player, StoredLocation location, long recordedAt) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(location, "location");
        BackHistory.Entry entry = new BackHistory.Entry(location, false, recordedAt > 0 ? recordedAt : System.currentTimeMillis());
        int capacity = config.get().back.historySize;
        Optional<AtriumUser> loaded = ctx.users().cached(player);
        if (loaded.isPresent()) {
            // A loaded user owns the component: change it there, or the next save would overwrite the row.
            AtomicBoolean added = new AtomicBoolean();
            loaded.get().update(store.component, state -> {
                if (newest(state.back(), location)) {
                    return state;
                }
                added.set(true);
                return state.withBack(state.back().push(entry, capacity));
            });
            return added.get() ? ctx.users().saveNow(player).thenApply(ignored -> true)
                    : CompletableFuture.completedFuture(false);
        }
        return store.load(player).thenCompose(stored -> {
            TeleportState state = stored == null ? TeleportState.DEFAULT : stored;
            if (newest(state.back(), location)) {
                return CompletableFuture.completedFuture(false);
            }
            return store.save(player, state.withBack(state.back().push(entry, capacity))).thenApply(ignored -> true);
        });
    }

    private static boolean newest(BackHistory history, StoredLocation location) {
        return history.latest().map(e -> e.location().equals(location)).orElse(false);
    }
}
