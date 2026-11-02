package dev.yanianz.atrium.module.chat;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CrossServerChatTest {
    private static final UUID SENDER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void roundTripsEveryChannel() throws Exception {
        for (CrossServerChat.Kind kind : CrossServerChat.Kind.values()) {
            String target = kind == CrossServerChat.Kind.PRIVATE ? "Alex" : "";
            CrossServerChat.Payload payload = new CrossServerChat.Payload(kind, SENDER, "Steve", target, "hello world");
            CrossServerChat.Payload decoded = CrossServerChat.decode(CrossServerChat.encode(payload));
            assertEquals(payload, decoded);
        }
    }

    @Test
    void rejectsEmptyOversizedAndTruncatedPayloads() {
        assertEquals(CrossServerChat.Reason.EMPTY, reason(new byte[0]));
        assertEquals(CrossServerChat.Reason.TOO_LARGE,
                reason(new byte[CrossServerChat.MAX_PAYLOAD_BYTES + 1]));
        assertEquals(CrossServerChat.Reason.MALFORMED, reason(new byte[] {1, 0}));
    }

    @Test
    void rejectsUnknownVersionAndKind() throws Exception {
        byte[] valid = CrossServerChat.encode(global("hello"));
        valid[0] = 2;
        assertEquals(CrossServerChat.Reason.VERSION, reason(valid));

        assertEquals(CrossServerChat.Reason.KIND,
                reason(raw(1, 9, SENDER, "Steve", "", "hello")));
    }

    @Test
    void rejectsInvalidSenderAndTargetNames() throws Exception {
        assertEquals(CrossServerChat.Reason.SENDER,
                reason(raw(1, 0, new UUID(0, 0), "Steve", "", "hello")));
        assertEquals(CrossServerChat.Reason.SENDER_NAME,
                reason(raw(1, 0, SENDER, "bad name!", "", "hello")));
        assertEquals(CrossServerChat.Reason.TARGET_NAME,
                reason(raw(1, 2, SENDER, "Steve", "bad name!", "hello")));
        assertEquals(CrossServerChat.Reason.TARGET_NAME,
                reason(raw(1, 0, SENDER, "Steve", "Alex", "hello")));
    }

    @Test
    void rejectsControlCharactersBlankAndOversizedText() throws Exception {
        assertEquals(CrossServerChat.Reason.TEXT,
                reason(raw(1, 0, SENDER, "Steve", "", "hi\u0000there")));
        assertEquals(CrossServerChat.Reason.TEXT,
                reason(raw(1, 0, SENDER, "Steve", "", "   ")));
        assertEquals(CrossServerChat.Reason.TEXT,
                reason(raw(1, 0, SENDER, "Steve", "",
                        "x".repeat(CrossServerChat.MAX_TEXT_CHARS + 1))));
    }

    @Test
    void rejectsTrailingBytes() throws Exception {
        byte[] valid = CrossServerChat.encode(global("hello"));
        byte[] longer = new byte[valid.length + 1];
        System.arraycopy(valid, 0, longer, 0, valid.length);
        longer[valid.length] = 7;
        assertEquals(CrossServerChat.Reason.TRAILING_BYTES, reason(longer));
    }

    @Test
    void encodeRefusesInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> CrossServerChat.encode(
                new CrossServerChat.Payload(CrossServerChat.Kind.PRIVATE, SENDER, "Steve", "", "hello")));
        assertThrows(IllegalArgumentException.class, () -> CrossServerChat.encode(
                new CrossServerChat.Payload(CrossServerChat.Kind.GLOBAL, SENDER, "Steve", "Alex", "hello")));
        assertThrows(IllegalArgumentException.class, () -> CrossServerChat.encode(
                new CrossServerChat.Payload(CrossServerChat.Kind.GLOBAL, SENDER, "Steve", "", "a\nb")));
    }

    @Test
    void outgoingTextStripsFormattingAndLimitsByCodePoints() {
        assertEquals("Hi there", CrossServerChat.outgoingText("\u00a7cHi\u00a7r there"));
        assertEquals("a b", CrossServerChat.outgoingText("  a\n\t b  "));
        assertEquals("", CrossServerChat.outgoingText(null));

        String longText = "x".repeat(CrossServerChat.MAX_TEXT_CHARS + 40);
        assertEquals(CrossServerChat.MAX_TEXT_CHARS,
                CrossServerChat.outgoingText(longText).length());

        String emoji = "\uD83D\uDE04".repeat(CrossServerChat.MAX_TEXT_CHARS + 10);
        String limited = CrossServerChat.outgoingText(emoji);
        assertEquals(CrossServerChat.MAX_TEXT_CHARS, limited.codePointCount(0, limited.length()));
        assertFalse(Character.isHighSurrogate(limited.charAt(limited.length() - 1)));
    }

    @Test
    void serverLabelsAndNamesAreValidated() {
        assertEquals("lobby-1", CrossServerChat.serverLabel("lobby-1"));
        assertEquals("unknown", CrossServerChat.serverLabel("<red>bad</red>"));
        assertEquals("unknown", CrossServerChat.serverLabel(""));
        assertTrue(CrossServerChat.validName("Steve_1"));
        assertFalse(CrossServerChat.validName("Steve!"));
        assertFalse(CrossServerChat.validName("a".repeat(17)));
    }

    private static CrossServerChat.Payload global(String text) {
        return new CrossServerChat.Payload(CrossServerChat.Kind.GLOBAL, SENDER, "Steve", "", text);
    }

    private static CrossServerChat.Reason reason(byte[] raw) {
        try {
            CrossServerChat.decode(raw);
            fail("payload should have been rejected");
            return null;
        } catch (CrossServerChat.InvalidPayloadException e) {
            return e.reason();
        }
    }

    private static byte[] raw(int version, int kind, UUID sender, String senderName, String target, String text)
            throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeByte(version);
            out.writeByte(kind);
            out.writeLong(sender.getMostSignificantBits());
            out.writeLong(sender.getLeastSignificantBits());
            out.writeUTF(senderName);
            out.writeUTF(target);
            out.writeUTF(text);
        }
        return buffer.toByteArray();
    }
}
