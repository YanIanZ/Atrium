package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.api.world.WorldResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileAndWorldTest {
    @Test
    void backHistoryRoundTripThroughProfileBytes() {
        BackHistory history = new BackHistory(List.of(
                new BackHistory.Entry(new StoredLocation("awf_island_1", 1.5, 70, -2, 90f, 0f), false, 1000L),
                new BackHistory.Entry(new StoredLocation("world", 0, 64, 0, 0f, 0f), true, 500L)));
        assertEquals(history, BackProfileData.decode(BackProfileData.encode(history)));
    }

    @Test
    void emptyHistoryIsNullAndNullIsEmpty() {
        assertNull(BackProfileData.encode(BackHistory.EMPTY));
        assertEquals(BackHistory.EMPTY, BackProfileData.decode(null));
        assertEquals(BackHistory.EMPTY, BackProfileData.decode(new byte[0]));
    }

    @Test
    void messageKeyPerWorldStatus() {
        assertEquals("teleport.world-not-loaded", StoredTeleports.messageKey(WorldResolver.Status.NOT_FOUND));
        assertEquals("teleport.world-unavailable-busy", StoredTeleports.messageKey(WorldResolver.Status.BUSY));
        assertEquals("teleport.world-unavailable-timeout", StoredTeleports.messageKey(WorldResolver.Status.TIMEOUT));
        assertEquals("teleport.world-unavailable-not-allowed", StoredTeleports.messageKey(WorldResolver.Status.NOT_ALLOWED));
        assertEquals("teleport.world-unavailable-failed", StoredTeleports.messageKey(WorldResolver.Status.FAILED));
    }
}
