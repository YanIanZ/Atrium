package dev.yanianz.atrium.module.effects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CooldownDecisionTest {
    @Test void missingExpiryIsAllowed() {
        assertTrue(CooldownDecision.decide(1_000L, null, 5_000L).allowed());
    }

    @Test void zeroCooldownIsAlwaysAllowed() {
        assertTrue(CooldownDecision.decide(1_000L, 9_999_999L, 0L).allowed());
        assertTrue(CooldownDecision.decide(1_000L, 9_999_999L, -5L).allowed());
    }

    @Test void runningCooldownReportsTheRemainingTime() {
        CooldownDecision decision = CooldownDecision.decide(1_000L, 6_000L, 5_000L);
        assertFalse(decision.allowed());
        assertEquals(Duration.ofSeconds(5), decision.remaining());
    }

    @Test void expiredCooldownIsAllowed() {
        assertTrue(CooldownDecision.decide(10_000L, 6_000L, 5_000L).allowed());
        assertTrue(CooldownDecision.decide(6_000L, 6_000L, 5_000L).allowed(), "expiry is exclusive");
    }

    @Test void allowedDecisionNeverCarriesRemainingTime() {
        CooldownDecision decision = new CooldownDecision(true, Duration.ofSeconds(3));
        assertEquals(Duration.ZERO, decision.remaining());
    }
}
