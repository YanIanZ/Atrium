package dev.yanianz.atrium.module.proxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ProxyEnvelopeTest {
    private static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE = new byte[16];
    private static final long NOW = 1_700_000_000_000L;

    static {
        for (int i = 0; i < NONCE.length; i++) {
            NONCE[i] = (byte) (i + 1);
        }
    }

    @Test
    void roundTripPreservesEveryField() throws Exception {
        byte[] payload = "hello network".getBytes(StandardCharsets.UTF_8);
        ProxyEnvelope original = ProxyEnvelope.of("lobby", "chat:staff", NOW, NONCE, payload);

        ProxyEnvelope decoded = ProxyEnvelope.decode(original.encode(SECRET), SECRET, NOW + 1000, 30_000);

        assertEquals("lobby", decoded.origin());
        assertEquals("chat:staff", decoded.topic());
        assertEquals(NOW, decoded.timestamp());
        assertArrayEquals(NONCE, decoded.nonce());
        assertArrayEquals(payload, decoded.payload());
    }

    @Test
    void tamperedPayloadByteFailsTheMac() {
        byte[] encoded = ProxyEnvelope.of("lobby", "economy", NOW, NONCE, new byte[] {1, 2, 3}).encode(SECRET);
        encoded[encoded.length - ProxyEnvelope.MAC_BYTES - 1] ^= 0x01;

        ProxyEnvelope.InvalidEnvelopeException error = assertThrows(ProxyEnvelope.InvalidEnvelopeException.class,
                () -> ProxyEnvelope.decode(encoded, SECRET, NOW, 30_000));
        assertEquals(ProxyEnvelope.Reason.MAC, error.reason());
    }

    @Test
    void tamperedMacByteIsRejected() {
        byte[] encoded = ProxyEnvelope.of("lobby", "economy", NOW, NONCE, new byte[] {1}).encode(SECRET);
        encoded[encoded.length - 1] ^= 0x40;

        ProxyEnvelope.InvalidEnvelopeException error = assertThrows(ProxyEnvelope.InvalidEnvelopeException.class,
                () -> ProxyEnvelope.decode(encoded, SECRET, NOW, 30_000));
        assertEquals(ProxyEnvelope.Reason.MAC, error.reason());
    }

    @Test
    void anotherSecretIsRejected() {
        byte[] encoded = ProxyEnvelope.of("lobby", "economy", NOW, NONCE, new byte[] {1}).encode(SECRET);
        byte[] other = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.US_ASCII);

        ProxyEnvelope.InvalidEnvelopeException error = assertThrows(ProxyEnvelope.InvalidEnvelopeException.class,
                () -> ProxyEnvelope.decode(encoded, other, NOW, 30_000));
        assertEquals(ProxyEnvelope.Reason.MAC, error.reason());
    }

    @Test
    void timestampOutsideTheWindowIsRejected() throws Exception {
        byte[] encoded = ProxyEnvelope.of("lobby", "economy", NOW, NONCE, new byte[] {1}).encode(SECRET);

        ProxyEnvelope.InvalidEnvelopeException future = assertThrows(ProxyEnvelope.InvalidEnvelopeException.class,
                () -> ProxyEnvelope.decode(encoded, SECRET, NOW + 30_001, 30_000));
        assertEquals(ProxyEnvelope.Reason.TIMESTAMP, future.reason());

        ProxyEnvelope.InvalidEnvelopeException past = assertThrows(ProxyEnvelope.InvalidEnvelopeException.class,
                () -> ProxyEnvelope.decode(encoded, SECRET, NOW - 30_001, 30_000));
        assertEquals(ProxyEnvelope.Reason.TIMESTAMP, past.reason());

        // Exactly on the boundary is accepted.
        assertEquals(NOW, assertDoesNotThrowDecode(encoded, NOW + 30_000));
    }

    @Test
    void unknownVersionIsRejected() {
        byte[] encoded = ProxyEnvelope.seal((byte) 9, "lobby", "economy", NOW, NONCE, new byte[] {1}, SECRET);

        ProxyEnvelope.InvalidEnvelopeException error = assertThrows(ProxyEnvelope.InvalidEnvelopeException.class,
                () -> ProxyEnvelope.decode(encoded, SECRET, NOW, 30_000));
        assertEquals(ProxyEnvelope.Reason.VERSION, error.reason());
    }

    @Test
    void payloadAtTheLimitIsAcceptedAndAboveItIsRefused() throws Exception {
        byte[] largest = new byte[ProxyEnvelope.MAX_PAYLOAD];
        Arrays.fill(largest, (byte) 7);
        ProxyEnvelope envelope = ProxyEnvelope.of("lobby", "bulk", NOW, NONCE, largest);
        ProxyEnvelope decoded = ProxyEnvelope.decode(envelope.encode(SECRET), SECRET, NOW, 30_000);
        assertArrayEquals(largest, decoded.payload());

        assertThrows(IllegalArgumentException.class,
                () -> ProxyEnvelope.of("lobby", "bulk", NOW, NONCE, new byte[ProxyEnvelope.MAX_PAYLOAD + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> ProxyEnvelope.create("lobby", "bulk", new byte[ProxyEnvelope.MAX_PAYLOAD + 1]));
    }

    @Test
    void shortOrGarbageMessagesAreMalformed() {
        ProxyEnvelope.InvalidEnvelopeException error = assertThrows(ProxyEnvelope.InvalidEnvelopeException.class,
                () -> ProxyEnvelope.decode(new byte[] {1, 2, 3}, SECRET, NOW, 30_000));
        assertEquals(ProxyEnvelope.Reason.MALFORMED, error.reason());
    }

    @Test
    void invalidTopicsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> ProxyEnvelope.requireTopic(""));
        assertThrows(IllegalArgumentException.class, () -> ProxyEnvelope.requireTopic("bad topic"));
        assertThrows(IllegalArgumentException.class, () -> ProxyEnvelope.requireTopic("x".repeat(65)));
    }

    private static long assertDoesNotThrowDecode(byte[] encoded, long now) throws Exception {
        return ProxyEnvelope.decode(encoded, SECRET, now, 30_000).timestamp();
    }
}
