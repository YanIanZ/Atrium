package dev.yanianz.atrium.module.messaging;

import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HelpOpPayloadTest {
    @Test void roundTripsEveryField() throws Exception {
        HelpOpPayload original = new HelpOpPayload("survival", "Alice", "please help with my base");
        Optional<HelpOpPayload> decoded = HelpOpPayload.decode(original.encode());
        assertTrue(decoded.isPresent());
        assertEquals(original, decoded.get());
    }

    @Test void keepsUntrustedCharactersAsData() throws Exception {
        HelpOpPayload original = new HelpOpPayload("sky-block", "Bob", "<red>not a tag</red> %player%");
        HelpOpPayload decoded = HelpOpPayload.decode(original.encode()).orElseThrow();
        assertEquals("<red>not a tag</red> %player%", decoded.message());
    }

    @Test void rejectsMalformedOrEmptyPayloads() {
        assertTrue(HelpOpPayload.decode(null).isEmpty());
        assertTrue(HelpOpPayload.decode(new byte[0]).isEmpty());
        assertTrue(HelpOpPayload.decode(new byte[] {1, 2, 3}).isEmpty());
    }

    @Test void capsFieldsToTheirWireLimits() throws Exception {
        HelpOpPayload huge = new HelpOpPayload("server", "Player", "x".repeat(5000));
        HelpOpPayload decoded = HelpOpPayload.decode(huge.encode()).orElseThrow();
        assertEquals(2048, decoded.message().length());
    }
}
