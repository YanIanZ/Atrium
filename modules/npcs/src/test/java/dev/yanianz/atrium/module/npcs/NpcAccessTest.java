package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NpcAccessTest {
    private static final BigDecimal FREE = BigDecimal.ZERO;

    @Test void permissionIsCheckedFirst() {
        assertEquals(NpcAccess.Outcome.NO_PERMISSION,
                NpcAccess.decide("atrium.npc.vip", false, Duration.ZERO, FREE, BigDecimal.TEN));
        assertEquals(NpcAccess.Outcome.ALLOWED,
                NpcAccess.decide("atrium.npc.vip", true, Duration.ZERO, FREE, BigDecimal.TEN));
        assertEquals(NpcAccess.Outcome.ALLOWED,
                NpcAccess.decide(null, false, Duration.ZERO, FREE, BigDecimal.TEN));
    }

    @Test void aRunningCooldownBlocksTheClick() {
        assertEquals(NpcAccess.Outcome.COOLDOWN,
                NpcAccess.decide(null, true, Duration.ofSeconds(3), FREE, BigDecimal.TEN));
        assertEquals(NpcAccess.Outcome.ALLOWED,
                NpcAccess.decide(null, true, Duration.ZERO, FREE, BigDecimal.TEN));
    }

    @Test void costNeedsAnEconomyAndEnoughMoney() {
        assertEquals(NpcAccess.Outcome.ALLOWED,
                NpcAccess.decide(null, true, Duration.ZERO, new BigDecimal("5"), new BigDecimal("5")));
        assertEquals(NpcAccess.Outcome.INSUFFICIENT_FUNDS,
                NpcAccess.decide(null, true, Duration.ZERO, new BigDecimal("5"), new BigDecimal("4.99")));
        assertEquals(NpcAccess.Outcome.NO_ECONOMY,
                NpcAccess.decide(null, true, Duration.ZERO, new BigDecimal("5"), null));
        assertEquals(NpcAccess.Outcome.ALLOWED,
                NpcAccess.decide(null, true, Duration.ZERO, FREE, null));
    }

    @Test void negativeCostsAreTreatedAsFree() {
        assertEquals(NpcAccess.Outcome.ALLOWED,
                NpcAccess.decide(null, true, Duration.ZERO, new BigDecimal("-1"), null));
    }
}
