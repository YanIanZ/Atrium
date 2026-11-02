package dev.yanianz.atrium.module.worlds;

import dev.yanianz.atrium.core.scheduler.Scheduler;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Small scheduling helpers of the worlds module. */
final class Tasks {
    private Tasks() {}

    /**
     * Runs on the global thread as a scheduled task, never inline in the caller (so a command answers with its
     * progress message before a world manager starts its main-thread work).
     */
    static <T> CompletableFuture<T> nextGlobal(Scheduler scheduler, Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            scheduler.global(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
