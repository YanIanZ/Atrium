package dev.yanianz.atrium.core.scheduler;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * Folia-native scheduling. The same code path runs on Paper and Folia:
 * <ul>
 *   <li>{@code global*}: world-independent game state (time, weather, broadcasts, global timers).</li>
 *   <li>{@code region*}: blocks and chunks at a location.</li>
 *   <li>{@code entity*}: anything touching one entity or player (inventory, health, teleport).
 *       {@code retired} runs instead when the entity was removed before the task ran.</li>
 *   <li>{@code async*}/{@code io}: never touch the world; use for storage and network.</li>
 * </ul>
 * Never use {@code Bukkit.getScheduler()}: it does not exist on Folia.
 */
public interface Scheduler {
    boolean isFolia();

    TaskHandle global(Runnable task);

    TaskHandle globalLater(Runnable task, long delayTicks);

    TaskHandle globalTimer(Runnable task, long delayTicks, long periodTicks);

    TaskHandle region(Location location, Runnable task);

    TaskHandle regionLater(Location location, Runnable task, long delayTicks);

    TaskHandle regionTimer(Location location, Runnable task, long delayTicks, long periodTicks);

    TaskHandle entity(Entity entity, Runnable task, @Nullable Runnable retired);

    TaskHandle entityLater(Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks);

    TaskHandle entityTimer(Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks, long periodTicks);

    TaskHandle async(Runnable task);

    TaskHandle asyncLater(Runnable task, Duration delay);

    TaskHandle asyncTimer(Runnable task, Duration delay, Duration period);

    /** Runs on the entity's thread (inline if already there). Fails with {@link EntityRetiredException}. */
    <T> CompletableFuture<T> supplyOnEntity(Entity entity, Supplier<T> supplier);

    /** Runs on the region owning the location (inline if already there). */
    <T> CompletableFuture<T> supplyAt(Location location, Supplier<T> supplier);

    /** Runs on the global region thread (inline if already there). */
    <T> CompletableFuture<T> supplyGlobal(Supplier<T> supplier);

    /** Runs blocking work (JDBC, HTTP, file I/O) on a virtual thread. */
    <T> CompletableFuture<T> supplyIo(Callable<T> callable);

    /** Executor backed by virtual threads for blocking I/O. */
    Executor io();

    final class EntityRetiredException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public EntityRetiredException(Entity entity) {
            super("Entity was removed before the task ran: " + entity.getUniqueId());
        }
    }
}
