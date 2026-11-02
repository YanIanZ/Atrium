package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WeatherKindTest {
    @Test
    void namesAndAliasesAreAccepted() {
        assertEquals(WeatherKind.CLEAR, WeatherKind.parse("clear"));
        assertEquals(WeatherKind.CLEAR, WeatherKind.parse("SUN"));
        assertEquals(WeatherKind.RAIN, WeatherKind.parse("rain"));
        assertEquals(WeatherKind.RAIN, WeatherKind.parse("downfall"));
        assertEquals(WeatherKind.STORM, WeatherKind.parse("Thunder"));
        assertEquals(WeatherKind.STORM, WeatherKind.parse(" storm "));
    }

    @Test
    void unknownNamesAreRejected() {
        assertNull(WeatherKind.parse("snow"));
        assertNull(WeatherKind.parse(""));
        assertNull(WeatherKind.parse(null));
    }

    @Test
    void flagsMatchTheWeather() {
        assertFalse(WeatherKind.CLEAR.storm());
        assertFalse(WeatherKind.CLEAR.thundering());
        assertTrue(WeatherKind.RAIN.storm());
        assertFalse(WeatherKind.RAIN.thundering());
        assertTrue(WeatherKind.STORM.storm());
        assertTrue(WeatherKind.STORM.thundering());
        assertEquals("storm", WeatherKind.STORM.key());
    }
}
