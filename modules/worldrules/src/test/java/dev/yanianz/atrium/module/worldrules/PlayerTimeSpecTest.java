package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalTime;
import org.junit.jupiter.api.Test;

class PlayerTimeSpecTest {
    @Test
    void namedTimesMapToVanillaTimes() {
        assertEquals(1000L, PlayerTimeSpec.parseTime("day"));
        assertEquals(6000L, PlayerTimeSpec.parseTime("NOON"));
        assertEquals(12000L, PlayerTimeSpec.parseTime("dusk"));
        assertEquals(13000L, PlayerTimeSpec.parseTime("night"));
        assertEquals(18000L, PlayerTimeSpec.parseTime("midnight"));
        assertEquals(23000L, PlayerTimeSpec.parseTime("dawn"));
    }

    @Test
    void clockTimesAndTicksAreAccepted() {
        assertEquals(6000L, PlayerTimeSpec.parseTime("12:00"));
        assertEquals(6500L, PlayerTimeSpec.parseTime("12:30"));
        assertEquals(18000L, PlayerTimeSpec.parseTime("0:00"));
        assertEquals(6000L, PlayerTimeSpec.parseTime("6000"));
        assertEquals(2000L, PlayerTimeSpec.parseTime("26000"));
    }

    @Test
    void badInputIsRejected() {
        assertNull(PlayerTimeSpec.parseTime("tomorrow"));
        assertNull(PlayerTimeSpec.parseTime("25:00"));
        assertNull(PlayerTimeSpec.parseTime("12:99"));
        assertNull(PlayerTimeSpec.parseTime("12:aa"));
        assertNull(PlayerTimeSpec.parse(null, LocalTime.NOON));
        assertNull(PlayerTimeSpec.parse("   ", LocalTime.NOON));
    }

    @Test
    void actionsAreParsed() {
        assertEquals(PlayerTimeSpec.Action.RESET, PlayerTimeSpec.parse("reset", LocalTime.NOON).action());
        assertEquals(PlayerTimeSpec.Action.FREEZE, PlayerTimeSpec.parse("freeze", LocalTime.NOON).action());
        assertEquals(PlayerTimeSpec.Action.UNFREEZE, PlayerTimeSpec.parse("unfreeze", LocalTime.NOON).action());
        PlayerTimeSpec.Spec set = PlayerTimeSpec.parse("night", LocalTime.NOON);
        assertEquals(PlayerTimeSpec.Action.SET, set.action());
        assertEquals(13000L, set.ticks());
    }

    @Test
    void realtimeUsesTheServerClock() {
        PlayerTimeSpec.Spec spec = PlayerTimeSpec.parse("realtime", LocalTime.of(12, 0));
        assertEquals(PlayerTimeSpec.Action.SET, spec.action());
        assertEquals(6000L, spec.ticks());
    }

    @Test
    void clockMappingWrapsAroundSixAm() {
        assertEquals(0L, PlayerTimeSpec.clockTicks(6, 0));
        assertEquals(18000L, PlayerTimeSpec.clockTicks(0, 0));
        assertEquals(500L, PlayerTimeSpec.clockTicks(6, 30));
    }

    @Test
    void describeInvertsTheClockMapping() {
        assertEquals("06:00", PlayerTimeSpec.describe(0));
        assertEquals("12:00", PlayerTimeSpec.describe(6000));
        assertEquals("18:00", PlayerTimeSpec.describe(12000));
        assertEquals("00:00", PlayerTimeSpec.describe(18000));
    }
}
