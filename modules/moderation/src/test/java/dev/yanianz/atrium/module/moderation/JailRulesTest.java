package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JailRulesTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Punishment jail(long id, Instant expires) {
        return new Punishment(id, PunishmentType.JAIL, PLAYER, "Steve", null, null, "Console", "r", T0, expires,
                null, null, null, "default");
    }

    @Test
    void expiredOfflineSentenceIsReleased() {
        JailRules.Decision decision = JailRules.decide(List.of(jail(1, T0.plusSeconds(60))), T0.plusSeconds(61));
        assertTrue(decision.release());
        assertEquals(List.of(1L), decision.expired());
    }

    @Test
    void storedMarkerAloneMeansTheSentenceEndedOffline() {
        // The expired row is no longer returned by the login query; the marker is what proves it must release.
        JailRules.Decision decision = JailRules.decide(List.of(), T0, true);
        assertTrue(decision.release());
        assertTrue(decision.expired().isEmpty());
        assertFalse(JailRules.decide(List.of(), T0, false).release(), "no marker, no pending release");
    }

    @Test
    void markerDoesNotReleaseWhileAJailIsStillActive() {
        assertFalse(JailRules.decide(List.of(jail(1, T0.plusSeconds(60))), T0, true).release());
    }

    @Test
    void activeJailIsNotReleased() {
        JailRules.Decision decision = JailRules.decide(List.of(jail(1, T0.plusSeconds(60))), T0.plusSeconds(30));
        assertFalse(decision.release());
        assertTrue(decision.expired().isEmpty());
    }

    @Test
    void permanentJailNeverExpires() {
        assertFalse(JailRules.decide(List.of(jail(1, null)), T0.plusSeconds(1_000_000)).release());
    }

    @Test
    void noJailRecordsMeansNothingToDo() {
        assertFalse(JailRules.decide(List.of(), T0).release());
        Punishment mute = new Punishment(2, PunishmentType.TEMPMUTE, PLAYER, "Steve", null, null, "Console", "r",
                T0, T0.plusSeconds(1), null, null, null, null);
        assertFalse(JailRules.decide(List.of(mute), T0.plusSeconds(10)).release());
    }

    @Test
    void newerActiveJailWinsOverAnExpiredRecord() {
        JailRules.Decision decision = JailRules.decide(
                List.of(jail(1, T0.plusSeconds(60)), jail(2, T0.plusSeconds(600))), T0.plusSeconds(61));
        assertFalse(decision.release(), "the newer sentence is still running");
        assertEquals(List.of(1L), decision.expired(), "the stale record can be dropped lazily");
    }
}
