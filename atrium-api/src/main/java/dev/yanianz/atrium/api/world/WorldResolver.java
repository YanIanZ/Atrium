package dev.yanianz.atrium.api.world;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.jspecify.annotations.Nullable;

/**
 * Finds a world by name, loading it on demand where the server supports that (SourbyCraft Aurora World
 * Fabric worlds, profile and instance worlds). Without such support only already loaded worlds are found
 * ({@link #bukkit()}).
 *
 * <p>Futures complete on no particular thread. Never join them on a tick thread: continue with
 * {@code whenComplete} and hop to the player's thread with the entity scheduler.
 */
public interface WorldResolver {
    /** Outcome of a lookup. */
    enum Status {
        /** The world was already loaded. */
        LOADED,
        /** The world was not loaded and has been loaded for this request. */
        LOADED_ON_DEMAND,
        /** No such world (not loaded and not known to an on-demand world manager). */
        NOT_FOUND,
        /** The world is known but loading on demand is not allowed for it. */
        NOT_ALLOWED,
        /** Another lifecycle operation (create, unload, clone) is running on the world. */
        BUSY,
        /** Loading did not finish within the configured time. */
        TIMEOUT,
        /** Loading failed. */
        FAILED;

        public boolean available() {
            return this == LOADED || this == LOADED_ON_DEMAND;
        }
    }

    /** Result of {@link #lookup}: the world when available, else the reason. */
    record Resolution(String worldName, @Nullable World world, Status status, String detail) {
        public Resolution {
            Objects.requireNonNull(worldName, "worldName");
            Objects.requireNonNull(status, "status");
            detail = detail == null ? "" : detail;
            if (status.available() && world == null) {
                throw new IllegalArgumentException("an available resolution needs a world");
            }
        }

        public static Resolution loaded(World world) {
            return new Resolution(world.getName(), world, Status.LOADED, "");
        }

        public static Resolution failed(String worldName, Status status, String detail) {
            return new Resolution(worldName, null, status, detail);
        }

        public Optional<World> optionalWorld() {
            return Optional.ofNullable(world);
        }

        public boolean available() {
            return status.available();
        }
    }

    /** Looks the world up, loading it on demand when supported. */
    CompletableFuture<Resolution> lookup(String worldName);

    /** The world, or empty when it is not available (see {@link #lookup} for the reason). */
    default CompletableFuture<Optional<World>> resolve(String worldName) {
        return lookup(worldName).thenApply(Resolution::optionalWorld);
    }

    /** Default resolver: only worlds that are already loaded ({@link Bukkit#getWorld(String)}). */
    static WorldResolver bukkit() {
        return name -> {
            World world = Bukkit.getWorld(name);
            return CompletableFuture.completedFuture(world != null
                    ? Resolution.loaded(world)
                    : Resolution.failed(name, Status.NOT_FOUND, "not loaded"));
        };
    }
}
