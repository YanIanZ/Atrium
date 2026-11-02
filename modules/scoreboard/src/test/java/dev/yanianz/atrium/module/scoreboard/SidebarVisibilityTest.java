package dev.yanianz.atrium.module.scoreboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The packet backend drops its state when a send fails, so a hide must not depend on that state: the
 * client keeps the objective until it receives an explicit removal.
 */
class SidebarVisibilityTest {
    @Test
    void hideStillRemovesTheObjectiveAfterTheStateWasDropped() {
        assertTrue(SidebarVisibility.needsRemoval(true, true));
    }

    @Test
    void hideAfterTheObjectiveWasAlreadyRemovedSendsNothing() {
        assertFalse(SidebarVisibility.needsRemoval(false, true));
    }

    @Test
    void offlinePlayerGetsNoPacket() {
        assertFalse(SidebarVisibility.needsRemoval(true, false));
        assertFalse(SidebarVisibility.needsRemoval(false, false));
    }
}
