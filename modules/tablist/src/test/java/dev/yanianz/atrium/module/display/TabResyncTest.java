package dev.yanianz.atrium.module.display;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/** Re-apply scheduling on TAB's load and reload events, with a fake global executor. */
class TabResyncTest {
    private static final UUID ALEX = new UUID(0, 1);
    private static final UUID STEVE = new UUID(0, 2);

    /** Collects scheduled tasks instead of running them, like a global scheduler between ticks. */
    private static final class FakeGlobal implements Executor {
        final Deque<Runnable> queued = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queued.add(task);
        }

        void tick() {
            for (int n = queued.size(); n > 0; n--) {
                queued.poll().run();
            }
        }
    }

    private final FakeGlobal global = new FakeGlobal();
    private final List<TabResync.Batch> batches = new ArrayList<>();
    private final TabResync resync = new TabResync(global, batches::add);

    @Test
    void playerLoadSchedulesOneRunForThatPlayer() {
        resync.playerLoaded(ALEX);
        assertEquals(1, global.queued.size(), "scheduled on the global thread, not run on TAB's thread");
        assertTrue(batches.isEmpty());
        global.tick();
        assertEquals(1, batches.size());
        TabResync.Batch batch = batches.getFirst();
        assertFalse(batch.all());
        assertEquals(Set.of(ALEX), batch.players());
        assertTrue(batch.affects(ALEX));
        assertFalse(batch.affects(STEVE));
        assertFalse(batch.onlyWake());
    }

    @Test
    void eventsBeforeTheRunAreCoalesced() {
        resync.playerLoaded(ALEX);
        resync.playerLoaded(STEVE);
        resync.wake();
        resync.playerLoaded(ALEX);
        assertEquals(1, global.queued.size());
        global.tick();
        assertEquals(List.of(new TabResync.Batch(false, Set.of(ALEX, STEVE))), batches);
    }

    @Test
    void tabReloadReappliesEveryone() {
        resync.playerLoaded(ALEX);
        resync.reloaded();
        global.tick();
        TabResync.Batch batch = batches.getFirst();
        assertTrue(batch.all());
        assertTrue(batch.affects(STEVE), "a reload drops the overrides of every player");
        // The flag is consumed: the next run only carries what happened after it.
        resync.wake();
        global.tick();
        assertFalse(batches.get(1).all());
        assertTrue(batches.get(1).onlyWake());
    }

    @Test
    void anEventDuringARunSchedulesTheNextRun() {
        List<TabResync.Batch> seen = new ArrayList<>();
        TabResync[] self = new TabResync[1];
        self[0] = new TabResync(global, batch -> {
            seen.add(batch);
            if (seen.size() == 1) {
                self[0].playerLoaded(STEVE); // TAB loads another player while Atrium re-applies
            }
        });
        self[0].playerLoaded(ALEX);
        global.tick();
        assertEquals(1, global.queued.size(), "the event that arrived during the run is not lost");
        global.tick();
        assertEquals(List.of(new TabResync.Batch(false, Set.of(ALEX)), new TabResync.Batch(false, Set.of(STEVE))), seen);
    }

    @Test
    void closedResyncNeitherSchedulesNorRuns() {
        resync.playerLoaded(ALEX);
        resync.close(); // TAB engine stopped (Atrium disabled, engine switch) after the run was scheduled
        global.tick();
        assertTrue(batches.isEmpty());
        resync.reloaded();
        resync.wake();
        assertTrue(global.queued.isEmpty());
        assertTrue(resync.closed());
    }
}
