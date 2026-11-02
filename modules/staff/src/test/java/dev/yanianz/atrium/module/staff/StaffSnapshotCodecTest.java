package dev.yanianz.atrium.module.staff;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StaffSnapshotCodecTest {
    private static StaffSnapshot sample() {
        return new StaffSnapshot(StaffSnapshot.CURRENT_FORMAT, "world_nether", 12.5, 64.0, -8.25, 90.5f, -12.0f,
                "CREATIVE", 30, 0.75f, 1234, true, false,
                new byte[] {1, 2, 3}, new byte[] {4, 5}, new byte[] {6}, new byte[] {7, 8, 9, 10}, Boolean.TRUE);
    }

    @Test
    void roundTripsEveryField() {
        StaffSnapshot decoded = StaffSnapshotCodec.decode(StaffSnapshotCodec.encode(sample()));
        assertEquals(StaffSnapshot.CURRENT_FORMAT, decoded.format());
        assertEquals("world_nether", decoded.world());
        assertEquals(12.5, decoded.x());
        assertEquals(64.0, decoded.y());
        assertEquals(-8.25, decoded.z());
        assertEquals(90.5f, decoded.yaw());
        assertEquals(-12.0f, decoded.pitch());
        assertEquals("CREATIVE", decoded.gameMode());
        assertEquals(30, decoded.level());
        assertEquals(0.75f, decoded.exp());
        assertEquals(1234, decoded.totalExperience());
        assertTrue(decoded.allowFlight());
        assertEquals(false, decoded.flying());
        assertArrayEquals(new byte[] {1, 2, 3}, decoded.storage());
        assertArrayEquals(new byte[] {4, 5}, decoded.armor());
        assertArrayEquals(new byte[] {6}, decoded.offhand());
        assertArrayEquals(new byte[] {7, 8, 9, 10}, decoded.effects());
        assertTrue(decoded.sameItems(sample()));
        assertEquals(Boolean.TRUE, decoded.vanished());
    }

    @Test
    void vanishStateRoundTripsAllThreeValues() {
        for (Boolean vanished : new Boolean[] {Boolean.TRUE, Boolean.FALSE, null}) {
            StaffSnapshot snapshot = new StaffSnapshot(StaffSnapshot.CURRENT_FORMAT, "world", 0, 0, 0, 0, 0,
                    "SURVIVAL", 0, 0, 0, false, false, new byte[0], new byte[0], new byte[0], new byte[0], vanished);
            assertEquals(vanished, StaffSnapshotCodec.decode(StaffSnapshotCodec.encode(snapshot)).vanished());
        }
    }

    @Test
    void formatOneRowsDecodeWithUnknownVanishState() {
        StaffSnapshot old = new StaffSnapshot(1, "world", 0, 0, 0, 0, 0, "SURVIVAL", 0, 0, 0, false, false,
                new byte[] {1}, new byte[0], new byte[0], new byte[0], Boolean.TRUE);
        StaffSnapshot decoded = StaffSnapshotCodec.decode(StaffSnapshotCodec.encode(old));
        assertEquals(1, decoded.format());
        assertNull(decoded.vanished(), "format 1 never stored the vanish state; it must not be guessed");
    }

    @Test
    void itemsEqualIsByteExact() {
        StaffSnapshot snapshot = sample();
        assertTrue(snapshot.itemsEqual(new byte[] {1, 2, 3}, new byte[] {4, 5}, new byte[] {6}));
        assertFalse(snapshot.itemsEqual(new byte[] {1, 2, 4}, new byte[] {4, 5}, new byte[] {6}));
        assertFalse(snapshot.itemsEqual(new byte[] {1, 2, 3}, new byte[] {4, 5}, new byte[0]));
    }

    @Test
    void accessorsReturnCopies() {
        StaffSnapshot snapshot = sample();
        snapshot.storage()[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, snapshot.storage());
    }

    @Test
    void rejectsGarbageAndTruncation() {
        assertThrows(IllegalArgumentException.class, () -> StaffSnapshotCodec.decode(new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> StaffSnapshotCodec.decode(new byte[] {1, 2, 3, 4, 0, 0, 0, 1}));
        byte[] encoded = StaffSnapshotCodec.encode(sample());
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 3);
        assertThrows(IllegalArgumentException.class, () -> StaffSnapshotCodec.decode(truncated));
    }

    @Test
    void rejectsAnUnknownFormat() {
        StaffSnapshot future = new StaffSnapshot(StaffSnapshot.CURRENT_FORMAT + 1, "world", 0, 0, 0, 0, 0,
                "SURVIVAL", 0, 0, 0, false, false, new byte[0], new byte[0], new byte[0], new byte[0], null);
        byte[] encoded = StaffSnapshotCodec.encode(future);
        assertThrows(IllegalArgumentException.class, () -> StaffSnapshotCodec.decode(encoded));
    }
}
