package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TeleportConfigTest {
    @Test
    void defaultsCoverEveryCause() {
        TeleportConfig config = new TeleportConfig();
        assertEquals(TeleportCause.values().length, config.warmupSeconds.size());
        assertEquals(0, config.warmup(TeleportCause.COMMAND));
        assertEquals(3, config.warmup(TeleportCause.HOME));
        assertEquals(0, config.cooldown(TeleportCause.HOME));
    }

    @Test
    void lookupIsCaseInsensitiveAndNeverNegative() {
        TeleportConfig config = new TeleportConfig();
        config.cooldownSeconds = Map.of(" home ", 30, "WARP", -5);
        assertEquals(30, config.cooldown(TeleportCause.HOME));
        assertEquals(0, config.cooldown(TeleportCause.WARP));
        assertEquals(0, config.cooldown(TeleportCause.SPAWN));
    }

    @Test
    void safeCauses() {
        TeleportConfig.SafeLanding safe = new TeleportConfig.SafeLanding();
        assertTrue(safe.appliesTo(TeleportCause.HOME));
        assertFalse(safe.appliesTo(TeleportCause.COMMAND));
        safe.causes = List.of("command");
        assertTrue(safe.appliesTo(TeleportCause.COMMAND));
        assertEquals(2, safe.searchRadius, "nearby columns are searched by default");
    }

    @Test
    void backDefaultsKeepADeathHistoryForEveryone() {
        TeleportConfig config = new TeleportConfig();
        assertTrue(config.back.onDeathForEveryone, "atrium.back.ondeath is granted by default");
        assertTrue(config.back.recordDeath);
        assertEquals(5, config.back.historySize);
    }

    @Test
    void automaticFeaturesStartOff() {
        TeleportConfig config = new TeleportConfig();
        assertFalse(config.elevator.enabled, "clicking a sign must not teleport until the elevator is enabled");
        assertFalse(config.launchpads.enabled);
        assertFalse(config.vehicle.enabled);
        assertEquals(50, config.jump.maxDistance);
        assertEquals(15, config.safeLanding.bypassWindowSeconds);
        assertTrue(config.safeLogin.enabled, "moving a player out of blocks is a safety fix");
    }

    @Test
    void requestReachAndItemListHaveSafeDefaults() {
        TeleportConfig config = new TeleportConfig();
        assertEquals(0, config.requests.maxDistance, "no reach limit unless configured");
        assertTrue(config.requests.blockedMaterials.isEmpty());
        assertTrue(RequestRules.withinDistance(1_000, config.requests.maxDistance));
    }

    @Test
    void respawnOrderDefaultsToTheVanillaOrderPlusHome() {
        TeleportConfig config = new TeleportConfig();
        assertEquals(List.of("anchor", "bed", "spawn", "home", "worldspawn"),
                RespawnPriority.orderFor(config.spawn.respawnOrder, "world"));
    }

    @Test
    void vehicleTypesAreCheckedCaseInsensitively() {
        TeleportConfig.Vehicle vehicle = new TeleportConfig.Vehicle();
        assertTrue(vehicle.allows("HORSE"));
        assertFalse(vehicle.allows("MINECART"));
        vehicle.types = List.of();
        assertTrue(vehicle.allows("MINECART"), "an empty list carries every rideable entity");
    }
}
