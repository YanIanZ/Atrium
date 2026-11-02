package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A write that is already in flight is never abandoned: its real result decides whether the importer
 * rolls back or confirms the other steps.
 */
class ImportWaitsTest {

    @Test
    void waitsForASlowWriteInsteadOfGivingUp() {
        CompletableFuture<String> slow = new CompletableFuture<>();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            slow.complete("stored");
        });
        writer.start();
        List<Long> notices = new CopyOnWriteArrayList<>();

        String result = ImportWaits.awaitWrite(slow, 5, 10, notices::add);

        assertEquals("stored", result);
        assertTrue(notices.size() >= 1, "a slow write should be reported while awaiting it");
    }

    @Test
    void aSlowFailureIsStillReported() {
        CompletableFuture<Void> slow = new CompletableFuture<>();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            slow.completeExceptionally(new IllegalStateException("storage refused"));
        });
        writer.start();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> ImportWaits.awaitWrite(slow, 5, 10, seconds -> {}));
        assertEquals("storage refused", failure.getCause().getMessage());
    }

    @Test
    void anAlreadyFailedWriteIsReportedWithoutWaiting() {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalArgumentException("no balance"));
        List<Long> notices = new CopyOnWriteArrayList<>();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> ImportWaits.awaitWrite(failed, 5, 10, notices::add));

        assertEquals("no balance", failure.getCause().getMessage());
        assertTrue(notices.isEmpty());
    }

    @Test
    void aCompletedWriteReturnsImmediately() {
        assertEquals("value", ImportWaits.awaitWrite(CompletableFuture.completedFuture("value"), 100, 100,
                seconds -> {
                    throw new AssertionError("no wait expected");
                }));
    }

    @Test
    void interruptionDoesNotAbandonTheWrite() throws InterruptedException {
        CompletableFuture<String> slow = new CompletableFuture<>();
        Thread writer = new Thread(() -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            slow.complete("stored");
        });
        writer.start();
        CompletableFuture<String> observed = slow.copy();
        Thread worker = Thread.currentThread();
        java.util.concurrent.CountDownLatch waiting = new java.util.concurrent.CountDownLatch(1);
        Thread interrupter = new Thread(() -> {
            try {
                if (!waiting.await(2, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            worker.interrupt();
        });
        interrupter.start();

        // The callback fires once awaitWrite is actually polling, so the interrupt cannot be missed by a
        // fast completion; the write finishes 200 ms later, well after the interrupt.
        assertEquals("stored", ImportWaits.awaitWrite(observed, 5, 10, seconds -> waiting.countDown()));
        assertTrue(Thread.interrupted(), "the interrupt flag must be restored");
    }

    @Test
    void noNoticeIsSentBeforeTheFirstInterval() {
        CompletableFuture<String> value = new CompletableFuture<>();
        List<Long> notices = new CopyOnWriteArrayList<>();
        value.complete("done");

        assertEquals("done", ImportWaits.awaitWrite(value, 5, 10_000, notices::add));
        assertFalse(notices.size() > 0);
    }
}
