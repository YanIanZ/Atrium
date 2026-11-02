package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpectateStateTest {
    @Test
    void startsInactive() {
        assertFalse(SpectateState.EMPTY.active());
        assertEquals("", SpectateState.EMPTY.target());
    }

    @Test
    void startedCapturesTheReturnPoint() {
        SpectateState state = SpectateState.started("world", 10, 64, -3, 90, 5, "SURVIVAL", "Notch");
        assertTrue(state.active());
        assertEquals("world", state.world());
        assertEquals(10, state.x());
        assertEquals("Notch", state.target());
    }

    @Test
    void retargetKeepsTheOriginalReturnPoint() {
        SpectateState first = SpectateState.started("world", 10, 64, -3, 90, 5, "SURVIVAL", "Notch");
        SpectateState second = first.retarget("Alex");
        assertEquals("Alex", second.target());
        assertEquals(first.world(), second.world());
        assertEquals(first.x(), second.x());
        assertEquals(first.gameMode(), second.gameMode());
    }

    @Test
    void anInactiveStateCannotBeRetargeted() {
        assertEquals(SpectateState.EMPTY, SpectateState.EMPTY.retarget("Alex"));
    }

    @Test
    void clearingResetsEverything() {
        SpectateState state = SpectateState.started("world", 1, 2, 3, 4, 5, "CREATIVE", "Notch");
        assertEquals(SpectateState.EMPTY, state.cleared());
    }
}
