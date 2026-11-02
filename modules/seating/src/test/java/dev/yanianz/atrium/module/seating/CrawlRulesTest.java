package dev.yanianz.atrium.module.seating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrawlRulesTest {
    @Test
    void fakeBlockSitsInTheHeadSpace() {
        assertEquals(65, CrawlRules.barrierY(64));
        assertEquals(0, CrawlRules.barrierY(-1));
    }

    @Test
    void sameBlockIgnoresNullAndOtherWorlds() {
        CrawlRules.Position position = new CrawlRules.Position("world", 1, 64, 3);
        assertFalse(CrawlRules.sameBlock(null, "world", 1, 64, 3));
        assertTrue(CrawlRules.sameBlock(position, "world", 1, 64, 3));
        assertFalse(CrawlRules.sameBlock(position, "world_nether", 1, 64, 3));
        assertFalse(CrawlRules.sameBlock(position, "world", 2, 64, 3));
        assertFalse(CrawlRules.sameBlock(position, "world", 1, 65, 3));
        assertFalse(CrawlRules.sameBlock(position, "world", 1, 64, 4));
    }

    @Test
    void crawlCanStartOnlyWhenStandingFree() {
        assertTrue(CrawlRules.canStart(false, false, false, false, false, false, false));
        assertFalse(CrawlRules.canStart(true, false, false, false, false, false, false));
        assertFalse(CrawlRules.canStart(false, true, false, false, false, false, false));
        assertFalse(CrawlRules.canStart(false, false, true, false, false, false, false));
        assertFalse(CrawlRules.canStart(false, false, false, true, false, false, false));
        assertFalse(CrawlRules.canStart(false, false, false, false, true, false, false));
        assertFalse(CrawlRules.canStart(false, false, false, false, false, true, false));
        assertFalse(CrawlRules.canStart(false, false, false, false, false, false, true));
    }

    @Test
    void jumpDetectionIgnoresWaterAndSmallSteps() {
        assertFalse(CrawlRules.jumped(64.0, 64.0, false));
        assertFalse(CrawlRules.jumped(64.0, 64.04, false));
        assertTrue(CrawlRules.jumped(64.0, 64.5, false));
        assertFalse(CrawlRules.jumped(64.0, 65.0, true));
    }

    @Test
    void stopConditionsCoverEveryUnsafeState() {
        assertFalse(CrawlRules.shouldStop(false, false, false, false, false));
        assertTrue(CrawlRules.shouldStop(true, false, false, false, false));
        assertTrue(CrawlRules.shouldStop(false, true, false, false, false));
        assertTrue(CrawlRules.shouldStop(false, false, true, false, false));
        assertTrue(CrawlRules.shouldStop(false, false, false, true, false));
        assertTrue(CrawlRules.shouldStop(false, false, false, false, true));
    }

    @Test
    void onlyPlayerInitiatedStopsShowAMessage() {
        assertTrue(CrawlRules.StopReason.TOGGLE.notifies());
        assertTrue(CrawlRules.StopReason.SNEAK.notifies());
        assertTrue(CrawlRules.StopReason.JUMP.notifies());
        assertTrue(CrawlRules.StopReason.DAMAGE.notifies());
        assertTrue(CrawlRules.StopReason.FLIGHT.notifies());
        assertTrue(CrawlRules.StopReason.GLIDE.notifies());
        assertFalse(CrawlRules.StopReason.QUIT.notifies());
        assertFalse(CrawlRules.StopReason.DEATH.notifies());
        assertFalse(CrawlRules.StopReason.TELEPORT.notifies());
        assertFalse(CrawlRules.StopReason.WORLD_CHANGE.notifies());
        assertFalse(CrawlRules.StopReason.VEHICLE.notifies());
        assertFalse(CrawlRules.StopReason.DISABLED.notifies());
    }

    @Test
    void positionTransitionsDriveTheBarrierUpdate() {
        CrawlRules.Position first = new CrawlRules.Position("world", 0, 64, 0);
        // Walking inside the same block must not resend a packet.
        assertTrue(CrawlRules.sameBlock(first, "world", 0, 64, 0));
        // Crossing a block boundary requires a new barrier.
        assertFalse(CrawlRules.sameBlock(first, "world", 1, 64, 0));
        // Going up one block (a staircase) also moves the head space.
        assertFalse(CrawlRules.sameBlock(first, "world", 0, 65, 0));
        CrawlRules.Position next = new CrawlRules.Position("world", 1, 64, 0);
        assertTrue(CrawlRules.sameBlock(next, "world", 1, 64, 0));
    }
}
