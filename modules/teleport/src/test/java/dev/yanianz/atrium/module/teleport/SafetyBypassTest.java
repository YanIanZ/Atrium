package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import org.junit.jupiter.api.Test;

class SafetyBypassTest {
    @Test
    void everyCauseHasItsOwnLowercaseNode() {
        for (TeleportCause cause : TeleportCause.values()) {
            assertEquals("atrium.teleport.safe.bypass." + cause.name().toLowerCase(java.util.Locale.ROOT),
                    SafetyBypass.node(cause));
        }
        assertEquals("atrium.teleport.safe.bypass.home", SafetyBypass.node(TeleportCause.HOME));
        assertEquals("atrium.teleport.safe.bypass.death_back", SafetyBypass.node(TeleportCause.DEATH_BACK));
    }

    @Test
    void nodesAreUniquePerCause() {
        long unique = java.util.Arrays.stream(TeleportCause.values()).map(SafetyBypass::node).distinct().count();
        assertEquals(TeleportCause.values().length, unique);
    }

    @Test
    void missingPermissibleNeverAllowsTheBypass() {
        org.junit.jupiter.api.Assertions.assertFalse(SafetyBypass.allows(null, TeleportCause.HOME));
    }
}
