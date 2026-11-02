package dev.yanianz.atrium.module.deathmessages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DamageHistoryTest {
    private final UUID victim = UUID.randomUUID();
    private final UUID attacker = UUID.randomUUID();

    private DamageHistory.Hit hit(UUID who, String cause, long at) {
        return new DamageHistory.Hit(who, who == null ? null : "Player", null, cause, at);
    }

    @Test
    void latestIsTheNewestAndOnlyInsideTheWindow() {
        DamageHistory history = new DamageHistory(4);
        history.record(victim, hit(attacker, "entity_attack", 1_000), 1_000, 5_000);
        history.record(victim, hit(attacker, "arrow", 2_000), 2_000, 5_000);
        assertEquals("arrow", history.latest(victim, 3_000, 5_000).orElseThrow().cause());
        assertTrue(history.latest(victim, 8_000, 5_000).isEmpty(), "older than the window");
        assertEquals(Optional.empty(), history.latest(UUID.randomUUID(), 2_000, 5_000));
    }

    @Test
    void zeroWindowDisablesTheHistory() {
        DamageHistory history = new DamageHistory(4);
        history.record(victim, hit(attacker, "entity_attack", 1_000), 1_000, 0);
        assertTrue(history.latest(victim, 1_000, 0).isEmpty());
    }

    @Test
    void previousSkipsTheSameAttackerBlockAndCause() {
        DamageHistory history = new DamageHistory(4);
        history.record(victim, hit(attacker, "entity_attack", 1_000), 1_000, 10_000);
        history.record(victim, hit(null, "fall", 2_000), 2_000, 10_000);
        DamageHistory.Hit current = history.latest(victim, 2_000, 10_000).orElseThrow();
        DamageHistory.Hit previous = history.previous(victim, current, 2_000, 10_000).orElseThrow();
        assertEquals("entity_attack", previous.cause());
        assertEquals(attacker, previous.attacker());
    }

    @Test
    void logIsBoundedAndPruned() {
        DamageHistory history = new DamageHistory(2);
        for (int i = 0; i < 10; i++) {
            history.record(victim, hit(attacker, "hit" + i, i * 100L), i * 100L, 100_000);
        }
        assertEquals("hit9", history.latest(victim, 900, 100_000).orElseThrow().cause());
        assertTrue(history.latest(victim, 200_000, 100_000).isEmpty(), "all entries expired");
    }

    @Test
    void clearRemovesOneVictimOnly() {
        DamageHistory history = new DamageHistory(4);
        UUID other = UUID.randomUUID();
        history.record(victim, hit(attacker, "entity_attack", 1), 1, 10);
        history.record(other, hit(attacker, "entity_attack", 1), 1, 10);
        history.clear(victim);
        assertTrue(history.latest(victim, 1, 10).isEmpty());
        assertTrue(history.latest(other, 1, 10).isPresent());
    }
}
