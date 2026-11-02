package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfileEnvelopeTest {
    @Test
    void roundTripIsDeterministic() throws Exception {
        Map<String, ProfileEnvelope.Entry> a = new LinkedHashMap<>();
        a.put("kits", new ProfileEnvelope.Entry(1, new byte[] {1, 2, 3}));
        a.put("back", new ProfileEnvelope.Entry(2, null));
        a.put("homes", new ProfileEnvelope.Entry(1, new byte[0]));
        Map<String, ProfileEnvelope.Entry> b = new LinkedHashMap<>();
        b.put("homes", a.get("homes"));
        b.put("back", a.get("back"));
        b.put("kits", a.get("kits"));
        byte[] encoded = ProfileEnvelope.encode(a);
        assertArrayEquals(encoded, ProfileEnvelope.encode(b));
        assertEquals(a, ProfileEnvelope.decode(encoded));
    }

    @Test
    void emptyEnvelopeIsNotEmptyBytes() throws Exception {
        byte[] encoded = ProfileEnvelope.encode(Map.of());
        assertEquals(7, encoded.length);
        assertEquals(Map.of(), ProfileEnvelope.decode(encoded));
    }

    @Test
    void enforcesTheTwoMebibyteLimit() throws Exception {
        byte[] almost = new byte[ProfileEnvelope.MAX_BYTES - 64];
        ProfileEnvelope.encode(Map.of("homes", new ProfileEnvelope.Entry(1, almost)));
        assertThrows(ProfileEnvelope.TooLargeException.class, () -> ProfileEnvelope.encode(
                Map.of("homes", new ProfileEnvelope.Entry(1, new byte[ProfileEnvelope.MAX_BYTES + 1]))));
        assertThrows(ProfileEnvelope.TooLargeException.class, () -> ProfileEnvelope.encode(Map.of(
                "a", new ProfileEnvelope.Entry(1, new byte[ProfileEnvelope.MAX_BYTES / 2 + 10]),
                "b", new ProfileEnvelope.Entry(1, new byte[ProfileEnvelope.MAX_BYTES / 2 + 10]))));
    }

    @Test
    void rejectsCorruptInput() throws Exception {
        byte[] good = ProfileEnvelope.encode(Map.of("homes", new ProfileEnvelope.Entry(1, new byte[] {9, 9})));
        byte[] magic = good.clone();
        magic[0] = 0;
        assertThrows(IOException.class, () -> ProfileEnvelope.decode(magic));
        byte[] format = good.clone();
        format[4] = 7;
        assertThrows(IOException.class, () -> ProfileEnvelope.decode(format));
        assertThrows(IOException.class, () -> ProfileEnvelope.decode(Arrays.copyOf(good, good.length - 1)));
        assertThrows(IOException.class, () -> ProfileEnvelope.decode(Arrays.copyOf(good, good.length + 1)));
        byte[] hugeLength = good.clone();
        hugeLength[good.length - 6] = 0x7f; // high byte of the entry length
        assertThrows(IOException.class, () -> ProfileEnvelope.decode(hugeLength));
        assertThrows(IOException.class, () -> ProfileEnvelope.decode(new byte[ProfileEnvelope.MAX_BYTES + 1]));
    }

    @Test
    void ownerEntryRoundTripsAndIsIgnoredAsData() throws Exception {
        UUID owner = UUID.fromString("11111111-2222-3333-4444-555555555555");
        Map<String, ProfileEnvelope.Entry> data = Map.of("homes", new ProfileEnvelope.Entry(1, new byte[] {1}));
        Map<String, ProfileEnvelope.Entry> decoded = ProfileEnvelope.decode(
                ProfileEnvelope.encode(ProfileEnvelope.withOwner(data, owner)));
        assertEquals(owner, ProfileEnvelope.owner(decoded));
        assertEquals(data.get("homes"), decoded.get("homes"));
        assertNull(ProfileEnvelope.owner(ProfileEnvelope.decode(ProfileEnvelope.encode(data))));
        assertEquals(Map.of(), ProfileEnvelope.withOwner(Map.of(), null));
    }

    @Test
    void corruptOwnerEntryIsRejected() {
        Map<String, ProfileEnvelope.Entry> bad = Map.of(ProfileEnvelope.OWNER_ENTRY, new ProfileEnvelope.Entry(1, new byte[3]));
        assertThrows(IOException.class, () -> ProfileEnvelope.owner(bad));
    }
}
