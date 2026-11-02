package dev.yanianz.atrium.module.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class HomesCodecTest {
    @Test
    void roundTripKeepsEveryField() throws Exception {
        Homes homes = Homes.of(List.of(
                new Home("home", new StoredLocation("world", 1.5, 64, -3.25, 90f, -10f), 1_700_000_000_000L),
                new Home("Island-2", new StoredLocation("awf_profile_ab12", -100, 70, 5, 0f, 0f), 42L)));
        Homes decoded = HomesCodec.INSTANCE.decode(HomesCodec.INSTANCE.encode(homes));
        assertEquals(homes, decoded);
        assertEquals("Island-2", decoded.get("island-2").orElseThrow().name());
    }

    @Test
    void emptyRoundTrip() throws Exception {
        assertEquals(Homes.EMPTY, HomesCodec.INSTANCE.decode(HomesCodec.INSTANCE.encode(Homes.EMPTY)));
    }

    @Test
    void rejectsCorruptData() throws Exception {
        byte[] good = HomesCodec.INSTANCE.encode(Homes.of(List.of(
                new Home("a", new StoredLocation("w", 0, 0, 0, 0, 0), 1))));
        byte[] wrongFormat = good.clone();
        wrongFormat[0] = 9;
        assertThrows(IOException.class, () -> HomesCodec.INSTANCE.decode(wrongFormat));
        assertThrows(IOException.class, () -> HomesCodec.INSTANCE.decode(Arrays.copyOf(good, good.length - 3)));
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        assertThrows(IOException.class, () -> HomesCodec.INSTANCE.decode(trailing));
        byte[] hugeCount = good.clone();
        hugeCount[1] = 0x7f;
        assertThrows(IOException.class, () -> HomesCodec.INSTANCE.decode(hugeCount));
    }
}
