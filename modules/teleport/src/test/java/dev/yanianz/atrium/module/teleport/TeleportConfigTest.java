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
}
