package dev.yanianz.atrium.module.itemtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GiveAllTargetsTest {
    @Test
    void onlinePlayersAreNeverQueued() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        List<UUID> result = GiveAllTargets.offlineRecipients(List.of(a, b), java.util.Set.of(a), 10);
        assertEquals(List.of(b), result);
    }

    @Test
    void duplicatesAreRemovedAndTheLimitIsHonoured() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        List<UUID> result = GiveAllTargets.offlineRecipients(List.of(a, a, b, c), java.util.Set.of(), 2);
        assertEquals(List.of(a, b), result);
        assertEquals(List.of(), GiveAllTargets.offlineRecipients(List.of(a), java.util.Set.of(), 0));
        assertEquals(List.of(), GiveAllTargets.offlineRecipients(List.of(), java.util.Set.of(), 10));
    }

    @Test
    void manyAccountsRespectTheConfiguredCap() {
        List<UUID> known = IntStream.range(0, 500).mapToObj(i -> UUID.randomUUID()).toList();
        assertEquals(100, GiveAllTargets.offlineRecipients(known, java.util.Set.of(), 100).size());
    }

    @Test
    void triggerIdsAreReadOnlyFromTheirExactSpelling() {
        assertEquals(Optional.of(AttachedTrigger.RIGHT), AttachedTrigger.byId("RIGHT"));
        assertEquals(Optional.of(AttachedTrigger.LEFT), AttachedTrigger.byId(" left "));
        assertEquals(Optional.of(AttachedTrigger.CONSUME), AttachedTrigger.byId("consume"));
        assertTrue(AttachedTrigger.byId("explode").isEmpty());
        assertTrue(AttachedTrigger.byId(null).isEmpty());
        assertEquals("right", AttachedTrigger.RIGHT.id());
        assertEquals(3, AttachedTrigger.values().length);
    }
}
