package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MuteChatStateTest {
    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Test
    void anEmptyStateIsNotMuted() {
        assertFalse(MuteChatState.NONE.muted(NOW));
        assertEquals(Optional.empty(), MuteChatState.NONE.remaining(NOW));
        assertEquals("fallback", MuteChatState.NONE.reasonOr("fallback"));
    }

    @Test
    void aDeadlineInTheFutureMutesAndOneInThePastDoesNot() {
        MuteChatState state = MuteChatState.NONE.until(NOW.plus(Duration.ofMinutes(5)), "spam", "Alex");
        assertTrue(state.muted(NOW));
        assertEquals(Optional.of(Duration.ofMinutes(5)), state.remaining(NOW));
        assertFalse(state.muted(NOW.plus(Duration.ofMinutes(5))), "the deadline itself ends the mute");
    }

    @Test
    void aReasonIsAlwaysReplacedByTheFallback() {
        MuteChatState state = MuteChatState.NONE.until(NOW.plusSeconds(1), "  ", "Alex");
        assertEquals("configured", state.reasonOr("configured"));
    }

    @Test
    void theStoredFormRoundTrips() {
        MuteChatState state = MuteChatState.NONE.until(NOW.plus(Duration.ofHours(2)), "spam", "Alex");
        MuteChatState restored = MuteChatState.decode(Long.toString(state.until().toEpochMilli()),
                state.reason(), state.actorName());
        assertEquals(state.until(), restored.until());
        assertEquals("spam", restored.reason());
        assertEquals("Alex", restored.actorName());

        MuteChatState none = MuteChatState.decode(null, null, null);
        assertEquals(MuteChatState.NONE, none);
    }

    @Test
    void anUnreadableDeadlineDoesNotMuteForever() {
        assertFalse(MuteChatState.decode("not-a-number", "r", "a").muted(NOW));
        assertFalse(MuteChatState.decode("0", "r", "a").muted(NOW));
    }

    @Test
    void reasonsAreTrimmedAndCapped() {
        assertEquals("hello", MuteChatState.normaliseReason("  hello ", "fallback"));
        assertEquals("fallback", MuteChatState.normaliseReason("   ", "fallback"));
        assertEquals(256, MuteChatState.normaliseReason("x".repeat(500), "fallback").length());
    }
}
