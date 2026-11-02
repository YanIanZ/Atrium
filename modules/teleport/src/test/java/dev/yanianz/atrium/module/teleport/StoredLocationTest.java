package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StoredLocationTest {
    @Test
    void roundTrip() {
        StoredLocation location = new StoredLocation("world_nether", -12.25, 64.0, 1e7, 90.5f, -45f);
        assertEquals(Optional.of(location), StoredLocation.decode(location.encode()));
    }

    @Test
    void worldNamesWithSeparatorRoundTrip() {
        StoredLocation location = new StoredLocation("odd;world", 1, 2, 3, 0, 0);
        assertEquals(Optional.of(location), StoredLocation.decode(location.encode()));
    }

    @Test
    void encodingIgnoresTheDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            StoredLocation location = new StoredLocation("w", 1.5, 2.5, 3.5, 0, 0);
            assertEquals("w;1.5;2.5;3.5;0.0;0.0", location.encode());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void rejectsMalformed() {
        for (String bad : new String[] {null, "", "world", "w;1;2;3;4", "w;x;2;3;4;5", "w;NaN;2;3;4;5", ";1;2;3;4;5"}) {
            assertTrue(StoredLocation.decode(bad).isEmpty(), "should reject: " + bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new StoredLocation("w", Double.POSITIVE_INFINITY, 0, 0, 0, 0));
    }

    @Test
    void clampsPitch() {
        assertEquals(90f, new StoredLocation("w", 0, 0, 0, 0, 200f).pitch());
    }
}
