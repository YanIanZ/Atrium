package dev.yanianz.atrium.module.player;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

class FlightPolicyTest {
    private static final Set<String> NONE = Set.of();
    private static final Set<String> FLY = Set.of("player:fly");
    private static final Set<String> ELYTRA = Set.of("elytra:launch");
    private static final Set<String> BOTH = Set.of("player:fly", "elytra:launch");

    @Test
    void claimsAllowFlightInEveryGameMode() {
        for (GameMode mode : GameMode.values()) {
            assertTrue(FlightPolicy.allowFlight(FLY, mode), "a claim must allow flight in " + mode);
            assertTrue(FlightPolicy.allowFlight(ELYTRA, mode), "any owner must allow flight in " + mode);
            assertTrue(FlightPolicy.allowFlight(BOTH, mode));
        }
    }

    @Test
    void creativeAndSpectatorAllowFlightWithoutClaims() {
        assertTrue(FlightPolicy.allowFlight(NONE, GameMode.CREATIVE));
        assertTrue(FlightPolicy.allowFlight(NONE, GameMode.SPECTATOR));
    }

    @Test
    void survivalAdventureAndNullModeNeedAClaim() {
        assertFalse(FlightPolicy.allowFlight(NONE, GameMode.SURVIVAL));
        assertFalse(FlightPolicy.allowFlight(NONE, GameMode.ADVENTURE));
        assertFalse(FlightPolicy.allowFlight(NONE, null));
    }

    @Test
    void releasingOneOwnerKeepsFlightForAnother() {
        // The elytra claim is still present after /fly releases its own owner.
        assertTrue(FlightPolicy.allowFlight(ELYTRA, GameMode.SURVIVAL));
        // And the other way around: the launch releasing its claim leaves /fly in control.
        assertTrue(FlightPolicy.allowFlight(FLY, GameMode.SURVIVAL));
        assertFalse(FlightPolicy.allowFlight(NONE, GameMode.SURVIVAL));
    }
}
