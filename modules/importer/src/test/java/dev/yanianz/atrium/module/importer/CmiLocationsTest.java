package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import org.junit.jupiter.api.Test;

class CmiLocationsTest {
    @Test
    void parsesLegacyLocationWithPitchThenYaw() {
        StoredLocation location = CmiLocations.parse("world:10:64:-5:12.5:45").orElseThrow();
        assertEquals("world", location.world());
        assertEquals(10, location.x());
        assertEquals(64, location.y());
        assertEquals(-5, location.z());
        assertEquals(45f, location.yaw());
        assertEquals(12.5f, location.pitch());
    }

    @Test
    void swapsWhenTheFourthValueCannotBePitch() {
        StoredLocation location = CmiLocations.parse("world:1:2:3:120:30").orElseThrow();
        assertEquals(120f, location.yaw());
        assertEquals(30f, location.pitch());
    }

    @Test
    void unescapesSeparatorsInsideTheWorldName() {
        assertEquals("my:world", CmiLocations.parse("myT8Cworld:1:2:3:0:0").orElseThrow().world());
        assertEquals("a;b", CmiLocations.parse("aT7Cb:1:2:3:0:0").orElseThrow().world());
    }

    @Test
    void escapeRoundTrips() {
        String raw = "a;b:c%d";
        assertEquals(raw, CmiLocations.unescape(CmiLocations.escape(raw)));
    }

    @Test
    void rejectsMalformedAndNonFiniteCoordinates() {
        assertTrue(CmiLocations.parse("world:1:2:3:0").isEmpty());
        assertTrue(CmiLocations.parse("world:NaN:2:3:0:0").isEmpty());
        assertTrue(CmiLocations.parse("world:1:Infinity:3:0:0").isEmpty());
        assertTrue(CmiLocations.parse("").isEmpty());
        assertTrue(CmiLocations.parse(null).isEmpty());
    }

    @Test
    void parsesJsonLocation() {
        StoredLocation location = CmiLocations.parse(
                "{\"world\":\"world\",\"x\":1,\"y\":2,\"z\":3,\"yaw\":90,\"pitch\":10}").orElseThrow();
        assertEquals("world", location.world());
        assertEquals(90f, location.yaw());
        assertEquals(10f, location.pitch());
    }

    @Test
    void parsesStoredLocationEncodingToo() {
        StoredLocation location = CmiLocations.parse("world;1.0;2.0;3.0;40.0;5.0").orElseThrow();
        assertEquals(1, location.x());
        assertEquals(40f, location.yaw());
        assertEquals(5f, location.pitch());
    }
}
