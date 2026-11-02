package dev.yanianz.atrium.module.elytra;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ShortCooldownsTest {
    @Test void acquireBlocksUntilExpiryPerPlayerAndKind() {
        ShortCooldowns cooldowns = new ShortCooldowns();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(0, cooldowns.tryAcquire(a, "boost", 1000, 1500));
        assertEquals(1000, cooldowns.tryAcquire(a, "boost", 1500, 1500));
        assertEquals(0, cooldowns.tryAcquire(a, "launch", 1500, 3000), "other kind is independent");
        assertEquals(0, cooldowns.tryAcquire(b, "boost", 1500, 1500), "other player is independent");
        assertEquals(0, cooldowns.tryAcquire(a, "boost", 2500, 1500), "expired");
        cooldowns.clear(a);
        assertEquals(0, cooldowns.tryAcquire(a, "boost", 2600, 1500));
        assertEquals(0, cooldowns.tryAcquire(a, "zero", 0, 0));
        assertEquals(0, cooldowns.tryAcquire(a, "zero", 0, 0), "zero duration never blocks");
    }
}
