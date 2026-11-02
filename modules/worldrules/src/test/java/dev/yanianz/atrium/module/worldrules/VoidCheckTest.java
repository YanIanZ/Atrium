package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class VoidCheckTest {
    @Test
    void triggerSitsBelowTheMinimumHeight() {
        assertEquals(-80.0, VoidCheck.triggerY(-64, 16));
        assertEquals(-64.0, VoidCheck.triggerY(-64, 0));
        assertEquals(-64.0, VoidCheck.triggerY(-64, -20));
    }

    @Test
    void belowComparesAgainstTheTrigger() {
        assertFalse(VoidCheck.below(-70, -64, 16));
        assertTrue(VoidCheck.below(-81, -64, 16));
        assertFalse(VoidCheck.below(Double.NaN, -64, 16));
    }

    @Test
    void aSafeSpotMustBeAboveTheTrigger() {
        assertTrue(VoidCheck.usableSafeSpot(64, -64, 16));
        assertFalse(VoidCheck.usableSafeSpot(-90, -64, 16));
    }

    @Test
    void worldListMatchesByNameAndEmptyMeansEverywhere() {
        WorldRulesConfig.VoidRescue rescue = new WorldRulesConfig.VoidRescue();
        assertTrue(rescue.inWorld("world"));
        rescue.worlds.add("world_nether");
        assertTrue(rescue.inWorld("WORLD_NETHER"));
        assertFalse(rescue.inWorld("world"));
    }

    @Test
    void speedUpWorldListMatchesByName() {
        WorldRulesConfig.SpeedUp speedUp = new WorldRulesConfig.SpeedUp();
        assertTrue(speedUp.inWorld("world"));
        speedUp.worlds.addAll(List.of("arena"));
        assertTrue(speedUp.inWorld("arena"));
        assertFalse(speedUp.inWorld("world"));
    }
}
