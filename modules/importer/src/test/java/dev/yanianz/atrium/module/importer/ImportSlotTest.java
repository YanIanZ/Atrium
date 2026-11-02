package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ImportSlotTest {
    private static final class Job {
        final AtomicBoolean finished = new AtomicBoolean();
    }

    @Test
    void onlyOneOfManyRacingStartsWins() throws Exception {
        for (int round = 0; round < 50; round++) {
            ImportSlot<Job> slot = new ImportSlot<>(job -> job.finished.get());
            int threads = 8;
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            List<Thread> started = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread thread = Thread.ofPlatform().start(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    // Regression: every racer passed "not running" and then started its own job.
                    if (!slot.busy() && slot.tryClaim(new Job())) {
                        winners.incrementAndGet();
                    }
                });
                started.add(thread);
            }
            go.countDown();
            for (Thread thread : started) {
                thread.join();
            }
            assertEquals(1, winners.get());
        }
    }

    @Test
    void aFinishedJobFreesTheSlot() {
        ImportSlot<Job> slot = new ImportSlot<>(job -> job.finished.get());
        Job first = new Job();
        assertTrue(slot.tryClaim(first));
        assertTrue(slot.busy());
        assertFalse(slot.tryClaim(new Job()));
        first.finished.set(true);
        assertFalse(slot.busy());
        Job second = new Job();
        assertTrue(slot.tryClaim(second));
        assertEquals(second, slot.get());
    }
}
