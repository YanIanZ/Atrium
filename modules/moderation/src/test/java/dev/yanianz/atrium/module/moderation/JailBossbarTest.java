package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The bar fills as the sentence is served; a permanent sentence never empties. */
class JailBossbarTest {
    private static final Instant START = Instant.parse("2026-01-01T12:00:00Z");

    private static Punishment sentence(Duration duration) {
        return new Punishment(1, PunishmentType.JAIL, UUID.randomUUID(), "Steve", null, null, "Console", "r",
                START, duration == null ? null : START.plus(duration), null, null, null, "cell");
    }

    @Test
    void aFreshSentenceIsFull() {
        assertEquals(1.0f, JailBossbar.progress(sentence(Duration.ofHours(1)), START), 0.001);
    }

    @Test
    void halfServedIsHalfFull() {
        assertEquals(0.5f, JailBossbar.progress(sentence(Duration.ofHours(1)), START.plus(Duration.ofMinutes(30))),
                0.001);
    }

    @Test
    void theBarNeverGoesBelowZeroOrAboveOne() {
        Punishment oneHour = sentence(Duration.ofHours(1));
        assertEquals(0.0f, JailBossbar.progress(oneHour, START.plus(Duration.ofHours(5))), 0.001);
        assertEquals(1.0f, JailBossbar.progress(oneHour, START.minus(Duration.ofHours(5))), 0.001);
    }

    @Test
    void aPermanentSentenceStaysFull() {
        assertEquals(1.0f, JailBossbar.progress(sentence(null), START.plus(Duration.ofDays(400))), 0.001);
    }

    @Test
    void aZeroLengthSentenceDoesNotDivideByZero() {
        assertEquals(0.0f, JailBossbar.progress(sentence(Duration.ZERO), START), 0.001);
    }
}
