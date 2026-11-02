package dev.yanianz.atrium.module.importer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongConsumer;

/**
 * Waits for a write that has already been handed to a service.
 *
 * <p>A write is never abandoned on a deadline: if the importer stopped listening while the write was
 * still running, it could land after the importer rolled the user's other steps back, leaving the user
 * half-imported. The real outcome is always awaited; progress is reported periodically so a slow
 * storage backend is visible instead of looking like a hang.
 */
final class ImportWaits {
    private ImportWaits() {}

    /**
     * @param future       write to observe; its completion is reported, never cancelled
     * @param pollMillis   how often completion is checked (at least 1 ms)
     * @param noticeMillis minimum interval between {@code onSlow} calls (at least 1 ms)
     * @param onSlow       called with the whole seconds elapsed when the write is still running
     * @return the completed value
     * @throws CompletionException when the write failed
     */
    static <T> T awaitWrite(CompletableFuture<T> future, long pollMillis, long noticeMillis, LongConsumer onSlow) {
        Objects.requireNonNull(future, "future");
        Objects.requireNonNull(onSlow, "onSlow");
        long poll = Math.max(1L, pollMillis);
        long notice = Math.max(1L, noticeMillis);
        long startedAt = System.nanoTime();
        long nextNotice = notice;
        boolean interrupted = false;
        while (true) {
            try {
                T value = future.get(poll, TimeUnit.MILLISECONDS);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return value;
            } catch (TimeoutException e) {
                long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;
                if (elapsedMillis >= nextNotice) {
                    nextNotice = elapsedMillis + notice;
                    onSlow.accept(elapsedMillis / 1000L);
                }
            } catch (InterruptedException e) {
                // The write is already in flight. Keep waiting and restore the flag before returning so an
                // interrupted thread still reports the real outcome instead of orphaning the change.
                interrupted = true;
            } catch (ExecutionException e) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw new CompletionException(cause);
            } catch (RuntimeException e) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw e;
            }
        }
    }
}
