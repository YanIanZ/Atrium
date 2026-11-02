package dev.yanianz.atrium.module.messaging;

import java.time.Duration;
import java.util.Objects;

/** Pure decision table for whether a /helpop request may be sent. */
final class HelpOpGate {
    enum Decision { ALLOW, MUTED, SHADOW_MUTED, COOLDOWN }

    private HelpOpGate() {}

    /**
     * Mutes win over everything else: a muted player is blocked outright, and a shadow-muted player gets a
     * neutral confirmation without anything reaching staff. A running cooldown is the last check.
     */
    static Decision decide(boolean muted, boolean shadowMuted, Duration cooldownLeft) {
        if (muted) {
            return Decision.MUTED;
        }
        if (shadowMuted) {
            return Decision.SHADOW_MUTED;
        }
        Duration left = Objects.requireNonNullElse(cooldownLeft, Duration.ZERO);
        return left.isZero() || left.isNegative() ? Decision.ALLOW : Decision.COOLDOWN;
    }
}
