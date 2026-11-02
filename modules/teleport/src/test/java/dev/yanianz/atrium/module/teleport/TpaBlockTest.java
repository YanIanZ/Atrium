package dev.yanianz.atrium.module.teleport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The /tpablock list: encoding, decisions and a storage round trip. */
class TpaBlockTest {
    @TempDir
    Path dir;

    private static final UUID ONE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TWO = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void theListDecidesWhoIsBlocked() {
        TeleportState state = TeleportState.DEFAULT.withBlockedRequests(Set.of(ONE));
        assertTrue(state.blocks(ONE));
        assertFalse(state.blocks(TWO));
        assertTrue(TeleportState.DEFAULT.blockedRequests().isEmpty());
        assertFalse(TeleportState.DEFAULT.blocks(ONE));
    }

    @Test
    void encodingIsStableAndIgnoresMalformedEntries() {
        TeleportState state = TeleportState.DEFAULT.withBlockedRequests(Set.of(TWO, ONE));
        assertEquals(ONE + "," + TWO, state.encodeBlockedRequests());
        assertEquals(Set.of(ONE, TWO), TeleportState.decodeBlockedRequests(state.encodeBlockedRequests()));
        assertEquals(Set.of(ONE), TeleportState.decodeBlockedRequests(ONE + ",not-a-uuid,"));
        assertTrue(TeleportState.decodeBlockedRequests(null).isEmpty());
        assertTrue(TeleportState.decodeBlockedRequests("  ").isEmpty());
    }

    @Test
    void changingAnotherValueKeepsTheBlockList() {
        TeleportState state = TeleportState.DEFAULT.withBlockedRequests(Set.of(ONE))
                .withAcceptsRequests(false)
                .withBack(TeleportState.DEFAULT.back());
        assertTrue(state.blocks(ONE));
        assertFalse(state.acceptsRequests());
    }

    @Test
    void theListSurvivesRestart() throws Exception {
        UUID player = UUID.randomUUID();
        try (Database db = open()) {
            new TeleportStore(db).save(player, TeleportState.DEFAULT.withBlockedRequests(Set.of(ONE, TWO))).get();
        }
        try (Database db = open()) {
            TeleportState loaded = new TeleportStore(db).load(player).get();
            assertEquals(Set.of(ONE, TWO), loaded.blockedRequests());
            assertTrue(loaded.blocks(ONE));
            assertTrue(loaded.blocks(TWO));
        }
    }

    private Database open() throws Exception {
        Database db = new Database(new CoreConfig.Database(), dir, Executors.newVirtualThreadPerTaskExecutor(),
                LoggerFactory.getLogger("test"));
        db.migrate("teleport", TeleportStore.MIGRATIONS);
        return db;
    }
}
