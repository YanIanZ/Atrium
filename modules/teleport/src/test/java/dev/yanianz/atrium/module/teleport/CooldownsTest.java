package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.yanianz.atrium.api.teleport.TeleportService.TeleportCause;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CooldownsTest {
    private final MutableClock clock = new MutableClock();
    private final Cooldowns cooldowns = new Cooldowns(clock);
    private final UUID player = UUID.randomUUID();

    @Test
    void perCauseCooldown() {
        cooldowns.start(player, TeleportCause.HOME, Duration.ofSeconds(10));
        assertEquals(Duration.ofSeconds(10), cooldowns.remaining(player, TeleportCause.HOME));
        assertEquals(Duration.ZERO, cooldowns.remaining(player, TeleportCause.WARP));
        clock.advance(Duration.ofSeconds(4));
        assertEquals(Duration.ofSeconds(6), cooldowns.remaining(player, TeleportCause.HOME));
        clock.advance(Duration.ofSeconds(6));
        assertEquals(Duration.ZERO, cooldowns.remaining(player, TeleportCause.HOME));
    }

    @Test
    void zeroLengthClears() {
        cooldowns.start(player, TeleportCause.HOME, Duration.ofSeconds(10));
        cooldowns.start(player, TeleportCause.HOME, Duration.ZERO);
        assertEquals(Duration.ZERO, cooldowns.remaining(player, TeleportCause.HOME));
    }

    @Test
    void clearAndPurge() {
        cooldowns.start(player, TeleportCause.HOME, Duration.ofSeconds(10));
        cooldowns.start(player, TeleportCause.BACK, Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        cooldowns.purge();
        assertEquals(Duration.ZERO, cooldowns.remaining(player, TeleportCause.BACK));
        cooldowns.clear(player);
        assertEquals(Duration.ZERO, cooldowns.remaining(player, TeleportCause.HOME));
    }
}
