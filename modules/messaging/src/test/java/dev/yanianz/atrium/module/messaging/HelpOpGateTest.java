package dev.yanianz.atrium.module.messaging;

import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HelpOpGateTest {
    @Test void allowsWhenNothingBlocks() {
        assertEquals(HelpOpGate.Decision.ALLOW, HelpOpGate.decide(false, false, Duration.ZERO));
        assertEquals(HelpOpGate.Decision.ALLOW, HelpOpGate.decide(false, false, null));
    }

    @Test void normalMuteWinsOverShadowAndCooldown() {
        assertEquals(HelpOpGate.Decision.MUTED,
                HelpOpGate.decide(true, true, Duration.ofSeconds(30)));
    }

    @Test void shadowMuteIsCheckedBeforeTheCooldown() {
        assertEquals(HelpOpGate.Decision.SHADOW_MUTED,
                HelpOpGate.decide(false, true, Duration.ofSeconds(30)));
    }

    @Test void positiveCooldownBlocksButZeroAndNegativeDoNot() {
        assertEquals(HelpOpGate.Decision.COOLDOWN,
                HelpOpGate.decide(false, false, Duration.ofMillis(1)));
        assertEquals(HelpOpGate.Decision.ALLOW,
                HelpOpGate.decide(false, false, Duration.ZERO));
        assertEquals(HelpOpGate.Decision.ALLOW,
                HelpOpGate.decide(false, false, Duration.ofMillis(-5)));
    }
}
