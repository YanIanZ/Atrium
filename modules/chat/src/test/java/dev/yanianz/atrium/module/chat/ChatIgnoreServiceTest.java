package dev.yanianz.atrium.module.chat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The lookup published as {@link dev.yanianz.atrium.api.chat.IgnoreService}, without a server. */
class ChatIgnoreServiceTest {
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();
    private static final UUID CAROL = UUID.randomUUID();

    @Test
    void reportsOnlyRealEntries() {
        Map<UUID, Set<UUID>> data = new HashMap<>();
        data.put(ALICE, Set.of(BOB));
        ChatIgnoreService service = new ChatIgnoreService(data::get);

        assertTrue(service.isIgnoring(ALICE, BOB));
        assertFalse(service.isIgnoring(ALICE, CAROL));
        assertFalse(service.isIgnoring(BOB, ALICE));
        assertFalse(service.isIgnoring(CAROL, ALICE));
    }

    @Test
    void selfAndMissingArgumentsAreNeverIgnored() {
        ChatIgnoreService service = new ChatIgnoreService(uuid ->
                uuid.equals(ALICE) ? Set.of(ALICE, BOB) : null);

        assertFalse(service.isIgnoring(ALICE, ALICE));
        assertFalse(service.isIgnoring(null, BOB));
        assertFalse(service.isIgnoring(ALICE, null));
        assertTrue(service.isIgnoring(ALICE, BOB));
    }

    @Test
    void unknownPlayersHaveAnEmptyList() {
        ChatIgnoreService service = new ChatIgnoreService(uuid -> null);

        assertTrue(service.ignored(ALICE).isEmpty());
        assertTrue(service.ignored(null).isEmpty());
        assertFalse(service.isIgnoring(ALICE, BOB));
    }

    @Test
    void returnedSetIsAnImmutableSnapshot() {
        Set<UUID> live = new HashSet<>(Set.of(BOB));
        ChatIgnoreService service = new ChatIgnoreService(uuid -> live);

        Set<UUID> snapshot = service.ignored(ALICE);
        live.add(CAROL);

        assertFalse(snapshot.contains(CAROL), "an earlier snapshot must not change");
        assertTrue(service.ignored(ALICE).contains(CAROL));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(CAROL));
    }
}
