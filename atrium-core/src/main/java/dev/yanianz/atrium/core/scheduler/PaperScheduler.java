package dev.yanianz.atrium.core.scheduler;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** {@link Scheduler} on top of Paper's region-aware schedulers (available on both Paper and Folia). */
public final class PaperScheduler implements Scheduler, AutoCloseable {
    private final Plugin plugin;
    private final Logger logger;
    private final boolean folia;
    private final ExecutorService io = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("atrium-io-", 0).factory());

    public PaperScheduler(Plugin plugin, Logger logger) {
        this.plugin = plugin;
        this.logger = logger;
        this.folia = detectFolia();
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public boolean isFolia() {
        return folia;
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                logger.error("Unhandled exception in scheduled task", t);
            }
        };
    }

    private static long ticks(long value) {
        return Math.max(1L, value);
    }

    @Override
    public TaskHandle global(Runnable task) {
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getGlobalRegionScheduler().run(plugin, t -> r.run()));
    }

    @Override
    public TaskHandle globalLater(Runnable task, long delayTicks) {
        if (delayTicks <= 0) {
            return global(task);
        }
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> r.run(), delayTicks));
    }

    @Override
    public TaskHandle globalTimer(Runnable task, long delayTicks, long periodTicks) {
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> r.run(), ticks(delayTicks), ticks(periodTicks)));
    }

    @Override
    public TaskHandle region(Location location, Runnable task) {
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getRegionScheduler().run(plugin, location, t -> r.run()));
    }

    @Override
    public TaskHandle regionLater(Location location, Runnable task, long delayTicks) {
        if (delayTicks <= 0) {
            return region(location, task);
        }
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getRegionScheduler().runDelayed(plugin, location, t -> r.run(), delayTicks));
    }

    @Override
    public TaskHandle regionTimer(Location location, Runnable task, long delayTicks, long periodTicks) {
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getRegionScheduler()
                .runAtFixedRate(plugin, location, t -> r.run(), ticks(delayTicks), ticks(periodTicks)));
    }

    @Override
    public TaskHandle entity(Entity entity, Runnable task, @Nullable Runnable retired) {
        Runnable r = guarded(task);
        TaskHandle handle = TaskHandle.of(entity.getScheduler().run(plugin, t -> r.run(), retired == null ? null : guarded(retired)));
        if (handle == TaskHandle.NOOP && retired != null) {
            guarded(retired).run();
        }
        return handle;
    }

    @Override
    public TaskHandle entityLater(Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks) {
        Runnable r = guarded(task);
        TaskHandle handle = TaskHandle.of(entity.getScheduler()
                .runDelayed(plugin, t -> r.run(), retired == null ? null : guarded(retired), ticks(delayTicks)));
        if (handle == TaskHandle.NOOP && retired != null) {
            guarded(retired).run();
        }
        return handle;
    }

    @Override
    public TaskHandle entityTimer(Entity entity, Runnable task, @Nullable Runnable retired, long delayTicks, long periodTicks) {
        Runnable r = guarded(task);
        TaskHandle handle = TaskHandle.of(entity.getScheduler().runAtFixedRate(
                plugin, t -> r.run(), retired == null ? null : guarded(retired), ticks(delayTicks), ticks(periodTicks)));
        if (handle == TaskHandle.NOOP && retired != null) {
            guarded(retired).run();
        }
        return handle;
    }

    @Override
    public TaskHandle async(Runnable task) {
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getAsyncScheduler().runNow(plugin, t -> r.run()));
    }

    @Override
    public TaskHandle asyncLater(Runnable task, Duration delay) {
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getAsyncScheduler()
                .runDelayed(plugin, t -> r.run(), Math.max(0, delay.toMillis()), TimeUnit.MILLISECONDS));
    }

    @Override
    public TaskHandle asyncTimer(Runnable task, Duration delay, Duration period) {
        Runnable r = guarded(task);
        return TaskHandle.of(Bukkit.getAsyncScheduler().runAtFixedRate(
                plugin, t -> r.run(), Math.max(0, delay.toMillis()), Math.max(1, period.toMillis()), TimeUnit.MILLISECONDS));
    }

    @Override
    public <T> CompletableFuture<T> supplyOnEntity(Entity entity, Supplier<T> supplier) {
        if (Bukkit.isOwnedByCurrentRegion(entity)) {
            return completeWith(supplier);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        var task = entity.getScheduler().run(plugin,
                t -> completeInto(future, supplier),
                () -> future.completeExceptionally(new EntityRetiredException(entity)));
        if (task == null) {
            future.completeExceptionally(new EntityRetiredException(entity));
        }
        return future;
    }

    @Override
    public <T> CompletableFuture<T> supplyAt(Location location, Supplier<T> supplier) {
        if (Bukkit.isOwnedByCurrentRegion(location)) {
            return completeWith(supplier);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getRegionScheduler().run(plugin, location, t -> completeInto(future, supplier));
        return future;
    }

    @Override
    public <T> CompletableFuture<T> supplyGlobal(Supplier<T> supplier) {
        if (Bukkit.isGlobalTickThread()) {
            return completeWith(supplier);
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().run(plugin, t -> completeInto(future, supplier));
        return future;
    }

    @Override
    public <T> CompletableFuture<T> supplyIo(Callable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        io.execute(() -> {
            try {
                future.complete(callable.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    @Override
    public ExecutorService io() {
        return io;
    }

    private static <T> CompletableFuture<T> completeWith(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        completeInto(future, supplier);
        return future;
    }

    private static <T> void completeInto(CompletableFuture<T> future, Supplier<T> supplier) {
        try {
            future.complete(supplier.get());
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
    }

    /** Stops accepting I/O work and waits briefly for running tasks (called on plugin disable). */
    @Override
    public void close() {
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("I/O tasks still running after 10s; continuing shutdown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
