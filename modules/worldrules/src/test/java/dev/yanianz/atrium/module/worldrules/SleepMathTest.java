package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SleepMathTest {
    @Test
    void percentUsesRealDivisionAndClamps() {
        assertEquals(0.0, SleepMath.percent(0, 10));
        assertEquals(0.25, SleepMath.percent(1, 4));
        assertEquals(1.0, SleepMath.percent(10, 10));
        assertEquals(0.0, SleepMath.percent(1, 0));
        assertEquals(1.0, SleepMath.percent(11, 10));
    }

    @Test
    void speedIsZeroBelowTheRequirement() {
        assertEquals(0.0, SleepMath.speed(0.4, 100, 0.5));
        assertEquals(50.0, SleepMath.speed(0.5, 100, 0.5));
        assertEquals(100.0, SleepMath.speed(1.0, 100, 0.5));
        assertEquals(1.0, SleepMath.speed(0.01, 100, 0.01));
    }

    @Test
    void nightDetectionCoversDuskToDawn() {
        assertFalse(SleepMath.isNight(0));
        assertFalse(SleepMath.isNight(1000));
        assertFalse(SleepMath.isNight(12000));
        assertTrue(SleepMath.isNight(13000));
        assertTrue(SleepMath.isNight(20000));
        assertFalse(SleepMath.isNight(23000));
        assertTrue(SleepMath.isNight(24000 + 13000));
        assertFalse(SleepMath.isNight(-1000));
    }

    @Test
    void timeAdvanceIgnoresInvalidSteps() {
        assertEquals(1000L, SleepMath.advance(900, 100));
        assertEquals(900L, SleepMath.advance(900, 0));
        assertEquals(900L, SleepMath.advance(900, Double.NaN));
        assertEquals(24400L, SleepMath.advance(24300, 100));
    }

    @Test
    void commandRootStripsSlashNamespaceAndArguments() {
        assertEquals("spawn", SleepMath.commandRoot("/spawn"));
        assertEquals("spawn", SleepMath.commandRoot("  //SPAWN home  "));
        assertEquals("msg", SleepMath.commandRoot("/minecraft:msg Bob hi"));
        assertEquals("", SleepMath.commandRoot("   "));
        assertEquals("", SleepMath.commandRoot(null));
    }

    @Test
    void allowedCommandsIgnoreCase() {
        WorldRulesConfig.Sleeping sleeping = new WorldRulesConfig.Sleeping();
        sleeping.allowedCommands.clear();
        sleeping.allowedCommands.add("msg");
        assertTrue(sleeping.allows("MSG"));
        assertFalse(sleeping.allows("spawn"));
        assertFalse(sleeping.allows(null));
    }
}
