package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ImportProgressTest {
    @Test
    void reportsPercentageAndEtaAtTheObservedRate() {
        AtomicLong clock = new AtomicLong(1_000L);
        ImportProgress progress = new ImportProgress(clock::get);
        progress.begin("users", 100);

        clock.set(11_000L);
        progress.update(25);
        ImportProgress.Snapshot snapshot = progress.snapshot(true);
        assertEquals(25.0, snapshot.percent());
        assertEquals("25%", snapshot.percentText());
        assertEquals(30L, snapshot.etaSeconds(), "10 s for 25 of 100 leaves 30 s");
        assertEquals("30s", snapshot.etaText());
        assertEquals(10_000L, snapshot.elapsedMillis());
    }

    @Test
    void finishedWorkHasNoEta() {
        AtomicLong clock = new AtomicLong(0L);
        ImportProgress progress = new ImportProgress(clock::get);
        progress.begin("warps", 4);
        clock.set(8_000L);
        progress.update(4);
        assertEquals(100.0, progress.snapshot(true).percent());
        assertEquals(0L, progress.snapshot(true).etaSeconds());
    }

    @Test
    void unknownTotalsDoNotDivideByZero() {
        assertEquals(0.0, ImportProgress.percent(0, 0));
        assertEquals(100.0, ImportProgress.percent(5, 0));
        assertEquals(0.0, ImportProgress.percent(-1, 10));
        assertEquals(100.0, ImportProgress.percent(11, 10));
        assertEquals(-1L, ImportProgress.etaSeconds(1_000L, 0, 10));
        assertEquals(-1L, ImportProgress.etaSeconds(1_000L, 2, 0));
    }

    @Test
    void formatsEtaReadably() {
        assertEquals("9s", ImportProgress.formatEta(9));
        assertEquals("1m 05s", ImportProgress.formatEta(65));
        assertEquals("1h 02m", ImportProgress.formatEta(3_725));
        assertEquals("?", ImportProgress.formatEta(-1));
    }
}
