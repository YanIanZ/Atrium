package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class StartedTeleportsTest {
    private final StartedTeleports started = new StartedTeleports();
    private final UUID player = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Test
    void aTeleportIsMarkedUntilItEnds() {
        assertFalse(started.isStarted(player));
        started.begin(player);
        assertTrue(started.isStarted(player));
        assertFalse(started.isStarted(other), "another player is not affected");
        started.end(player);
        assertFalse(started.isStarted(player), "the listener must record the next teleport of this player again");
    }

    @Test
    void overlappingTeleportsAreCounted() {
        started.begin(player);
        started.begin(player);
        started.end(player);
        assertTrue(started.isStarted(player), "one teleport is still in flight");
        started.end(player);
        assertFalse(started.isStarted(player));
    }

    @Test
    void endingWithoutStartingDoesNotCreateAnEntry() {
        started.end(player);
        assertFalse(started.isStarted(player));
        started.begin(player);
        started.end(player);
        started.end(player);
        assertFalse(started.isStarted(player));
    }

    @Test
    void clearDropsEveryStartedTeleportOfThePlayer() {
        started.begin(player);
        started.begin(player);
        started.clear(player);
        assertFalse(started.isStarted(player), "quitting drops every mark so nothing leaks");
        started.begin(other);
        started.clearAll();
        assertFalse(started.isStarted(other));
    }
}
