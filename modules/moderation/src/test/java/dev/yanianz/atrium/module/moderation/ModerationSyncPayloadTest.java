package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModerationSyncPayloadTest {
    private static final UUID ID = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
    private static final long NOW = 1_700_000_000_000L;

    private static ModerationSyncPayload.Payload sample(
            ModerationSyncPayload.Action action, long expires, String ip) {
        return new ModerationSyncPayload.Payload(action, ID, "Notch", "Cheating", "Admin",
                NOW - 10_000, expires, ip);
    }

    @Test
    void everyActionRoundTrips() throws Exception {
        for (ModerationSyncPayload.Action action : ModerationSyncPayload.Action.values()) {
            String ip = action.needsIp() ? "10.0.0.0/8" : null;
            long expires = action == ModerationSyncPayload.Action.BAN ? 0 : NOW + 3_600_000;
            ModerationSyncPayload.Payload original = sample(action, action.needsIp() ? 0 : expires, ip);
            ModerationSyncPayload.Payload decoded = ModerationSyncPayload.decode(
                    ModerationSyncPayload.encode(original), NOW);
            assertEquals(original.action(), decoded.action());
            assertEquals(original.uuid(), decoded.uuid());
            assertEquals(original.name(), decoded.name());
            assertEquals(original.reason(), decoded.reason());
            assertEquals(original.actor(), decoded.actor());
            assertEquals(original.created(), decoded.created());
            assertEquals(original.expires(), decoded.expires());
            assertEquals(original.ip(), decoded.ip());
        }
    }

    @Test
    void ipBanWithoutAccountIsValid() throws Exception {
        ModerationSyncPayload.Payload payload = new ModerationSyncPayload.Payload(
                ModerationSyncPayload.Action.IPBAN, null, "10.0.0.7", "Proxy abuse", "Console",
                NOW - 1000, 0, "10.0.0.7");
        ModerationSyncPayload.Payload decoded = ModerationSyncPayload.decode(
                ModerationSyncPayload.encode(payload), NOW);
        assertNull(decoded.uuid());
        assertEquals("10.0.0.7", decoded.ip());
    }

    @Test
    void addressesAreNormalisedOnDecode() throws Exception {
        ModerationSyncPayload.Payload payload = new ModerationSyncPayload.Payload(
                ModerationSyncPayload.Action.IPBAN, null, "2001:DB8:0:0:0:0:0:1", "reason", "actor",
                NOW - 1000, 0, "2001:DB8:0:0:0:0:0:1");
        assertEquals("2001:db8::1", ModerationSyncPayload.decode(
                ModerationSyncPayload.encode(payload), NOW).ip());
    }

    @Test
    void expiredPayloadIsValidButDoesNotApply() throws Exception {
        ModerationSyncPayload.Payload payload = sample(
                ModerationSyncPayload.Action.MUTE, NOW - 5_000, null);
        ModerationSyncPayload.Payload decoded = ModerationSyncPayload.decode(
                ModerationSyncPayload.encode(payload), NOW);
        assertTrue(decoded.isExpired(NOW));
        assertFalse(ModerationSyncPayload.decode(
                ModerationSyncPayload.encode(sample(ModerationSyncPayload.Action.MUTE, 0, null)), NOW).isExpired(NOW));
    }

    @Test
    void wrongVersionIsRejected() throws Exception {
        byte[] raw = raw(ModerationSyncPayload.Action.BAN, ID.toString(), "Notch", "reason", "Admin",
                NOW - 1000, 0, "", 2);
        assertReason(ModerationSyncPayload.Reason.VERSION, raw);
    }

    @Test
    void unknownActionIsRejected() throws Exception {
        byte[] raw = raw(ModerationSyncPayload.Action.BAN, ID.toString(), "Notch", "reason", "Admin",
                NOW - 1000, 0, "", 1);
        raw = replaceAction(raw, "DROP_TABLE");
        assertReason(ModerationSyncPayload.Reason.ACTION, raw);
    }

    @Test
    void accountActionsNeedAValidUuid() throws Exception {
        assertReason(ModerationSyncPayload.Reason.UUID,
                raw(ModerationSyncPayload.Action.BAN, "", "Notch", "r", "a", NOW - 1000, 0, "", 1));
        assertReason(ModerationSyncPayload.Reason.UUID,
                raw(ModerationSyncPayload.Action.BAN, "not-a-uuid", "Notch", "r", "a", NOW - 1000, 0, "", 1));
    }

    @Test
    void ipActionsNeedAValidIp() throws Exception {
        assertReason(ModerationSyncPayload.Reason.IP,
                raw(ModerationSyncPayload.Action.IPBAN, "", "1.2.3.4", "r", "a", NOW - 1000, 0, "", 1));
        assertReason(ModerationSyncPayload.Reason.IP,
                raw(ModerationSyncPayload.Action.IPBAN, "", "1.2.3.4", "r", "a", NOW - 1000, 0, "garbage", 1));
        assertReason(ModerationSyncPayload.Reason.IP,
                raw(ModerationSyncPayload.Action.IPBAN, "", "1.2.3.4", "r", "a", NOW - 1000, 0, "1.2.3.4/33", 1));
    }

    @Test
    void timestampsAreValidated() throws Exception {
        assertReason(ModerationSyncPayload.Reason.TIME,
                raw(ModerationSyncPayload.Action.MUTE, ID.toString(), "Notch", "r", "a",
                        NOW - 10_000, NOW - 20_000, "", 1));
        assertReason(ModerationSyncPayload.Reason.TIME,
                raw(ModerationSyncPayload.Action.MUTE, ID.toString(), "Notch", "r", "a",
                        NOW - 10_000, -1, "", 1));
        assertReason(ModerationSyncPayload.Reason.TIME,
                raw(ModerationSyncPayload.Action.MUTE, ID.toString(), "Notch", "r", "a",
                        NOW + Duration.ofHours(1).toMillis(), 0, "", 1));
        assertReason(ModerationSyncPayload.Reason.TIME,
                raw(ModerationSyncPayload.Action.MUTE, ID.toString(), "Notch", "r", "a",
                        NOW - 10_000, NOW + Duration.ofDays(365L * 200).toMillis(), "", 1));
    }

    @Test
    void textIsLengthCheckedAndControlCharactersRejected() throws Exception {
        assertReason(ModerationSyncPayload.Reason.TEXT,
                raw(ModerationSyncPayload.Action.MUTE, ID.toString(), "Notch", "x".repeat(513), "a",
                        NOW - 1000, 0, "", 1));
        assertReason(ModerationSyncPayload.Reason.TEXT,
                raw(ModerationSyncPayload.Action.MUTE, ID.toString(), "Bad\u00a7Name", "r", "a",
                        NOW - 1000, 0, "", 1));
        assertReason(ModerationSyncPayload.Reason.TEXT,
                raw(ModerationSyncPayload.Action.MUTE, ID.toString(), "Notch", "line\nbreak", "a",
                        NOW - 1000, 0, "", 1));
    }

    @Test
    void oversizedOrTruncatedDataIsRejected() {
        assertReason(ModerationSyncPayload.Reason.SIZE, new byte[0]);
        assertReason(ModerationSyncPayload.Reason.SIZE, new byte[ModerationSyncPayload.MAX_BYTES + 1]);
        assertReason(ModerationSyncPayload.Reason.SIZE, new byte[] {1, 2, 3});
    }

    @Test
    void trailingBytesAreRejected() throws Exception {
        byte[] valid = ModerationSyncPayload.encode(sample(ModerationSyncPayload.Action.MUTE, 0, null));
        byte[] padded = new byte[valid.length + 1];
        System.arraycopy(valid, 0, padded, 0, valid.length);
        assertReason(ModerationSyncPayload.Reason.SIZE, padded);
    }

    private static byte[] raw(ModerationSyncPayload.Action action, String uuid, String name, String reason,
            String actor, long created, long expires, String ip, int version) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(version);
            out.writeUTF(action.name());
            out.writeUTF(uuid);
            out.writeUTF(name);
            out.writeUTF(reason);
            out.writeUTF(actor);
            out.writeLong(created);
            out.writeLong(expires);
            out.writeUTF(ip);
        }
        return buffer.toByteArray();
    }

    /** Rewrites only the action field of a well-formed message. */
    private static byte[] replaceAction(byte[] raw, String action) throws IOException {
        try (var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw))) {
            in.readUnsignedByte();
            in.readUTF();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeByte(ModerationSyncPayload.VERSION);
                out.writeUTF(action);
                out.writeUTF(in.readUTF());
                out.writeUTF(in.readUTF());
                out.writeUTF(in.readUTF());
                out.writeUTF(in.readUTF());
                out.writeLong(in.readLong());
                out.writeLong(in.readLong());
                out.writeUTF(in.readUTF());
            }
            return buffer.toByteArray();
        }
    }

    private static void assertReason(ModerationSyncPayload.Reason reason, byte[] data) {
        ModerationSyncPayload.InvalidPayloadException error = assertThrows(
                ModerationSyncPayload.InvalidPayloadException.class,
                () -> ModerationSyncPayload.decode(data, NOW));
        assertEquals(reason, error.reason());
    }
}
