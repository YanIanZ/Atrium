package dev.yanianz.atrium.module.playtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The live counter must continue from an imported total instead of overwriting it on the next save. */
class PlaytimeSessionTest {
    private static PlaytimeSession ready(long total, long today) {
        PlaytimeSession session = new PlaytimeSession(UUID.randomUUID(), ZoneId.of("UTC"));
        session.loaded(total, today);
        return session;
    }

    @Test
    void addAdjustsTheStoredBaseAndKeepsSessionSecondsOnTop() {
        PlaytimeSession session = ready(100, 40);
        session.addStored(25);
        assertEquals(125, session.total());
        assertEquals(65, session.today());
    }

    @Test
    void setMakesTheLiveTotalMatchTheImportedValue() {
        PlaytimeSession session = ready(100, 40);
        session.setStoredTotal(500, 0, 0);
        assertEquals(500, session.total());
        assertEquals(500, session.today());
    }

    /**
     * Regression: the join load is ordered with administrative writes, so a write that is applied before
     * {@code loaded} is already part of the read and must not be added a second time.
     */
    @Test
    void anAdministrativeAddBeforeTheLoadIsNotCountedTwice() {
        PlaytimeSession session = new PlaytimeSession(UUID.randomUUID(), ZoneId.of("UTC"));
        assertFalse(session.ready());
        session.addStored(1_000);
        // The queued load reads storage after that add was committed.
        session.loaded(1_100, 100);
        assertEquals(1_100, session.total(), "the read already contains the added 1000 seconds");
        assertEquals(100, session.today());
    }

    @Test
    void anAdministrativeSetBeforeTheLoadIsNotAppliedOnTopOfTheRead() {
        PlaytimeSession session = new PlaytimeSession(UUID.randomUUID(), ZoneId.of("UTC"));
        session.setStoredTotal(500, 0, 0);
        session.loaded(500, 500);
        assertEquals(500, session.total());
        assertEquals(500, session.today());
    }

    @Test
    void negativeDurationsAreRejected() {
        assertEquals(90, PlaytimeModule.requireSeconds(Duration.ofSeconds(90)));
        assertThrows(IllegalArgumentException.class, () -> PlaytimeModule.requireSeconds(Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> PlaytimeModule.requireSeconds(null));
    }

    @Test
    void aFinishedSessionStopsCounting() {
        PlaytimeSession session = ready(100, 100);
        session.finish(60);
        assertTrue(session.finished());
        assertEquals(0, session.tick(true, 60));
    }
}
