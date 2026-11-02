package dev.yanianz.atrium.module.inventories;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetentionTest {
    private static Retention.Entry entry(long id, long created) {
        return new Retention.Entry(id, created);
    }

    @Test
    void keepsOnlyTheNewestSnapshots() {
        List<Retention.Entry> entries = List.of(entry(1, 10), entry(3, 30), entry(2, 20));
        assertEquals(List.of(2L, 1L), Retention.toDelete(entries, 1, 0));
    }

    @Test
    void ageCutoffRemovesOldSnapshotsEvenWhenTheyAreAmongTheNewest() {
        long now = 1_000_000L;
        List<Retention.Entry> entries = List.of(
                entry(3, now - 1_000),
                entry(2, now - 5_000),
                entry(1, now - 50_000));
        // Keep ten, but everything older than ten seconds goes away.
        assertEquals(List.of(1L), Retention.toDelete(entries, 10, now - 10_000));
    }

    @Test
    void countAndAgeLimitsAreCombined() {
        long now = 1_000_000L;
        List<Retention.Entry> entries = List.of(
                entry(4, now - 1_000),
                entry(3, now - 2_000),
                entry(2, now - 50_000),
                entry(1, now - 60_000));
        assertEquals(List.of(2L, 1L), Retention.toDelete(entries, 2, now - 10_000));
    }

    @Test
    void zeroCountRemovesEverything() {
        assertEquals(List.of(2L, 1L), Retention.toDelete(List.of(entry(1, 1), entry(2, 2)), 0, 0));
    }

    @Test
    void zeroCutoffMeansNoAgeLimit() {
        assertEquals(List.of(), Retention.toDelete(List.of(entry(1, 1), entry(2, 2)), 10, 0));
    }

    @Test
    void duplicateInputsNeverProduceDuplicateIds() {
        List<Retention.Entry> entries = List.of(entry(2, 2), entry(2, 2), entry(1, 1));
        assertEquals(List.of(1L), Retention.toDelete(entries, 1, 0));
    }
}
