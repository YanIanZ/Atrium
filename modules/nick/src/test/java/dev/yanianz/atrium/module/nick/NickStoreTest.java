package dev.yanianz.atrium.module.nick;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class NickStoreTest {
    @TempDir Path directory;

    private Database database() {
        return new Database(new CoreConfig.Database(), directory, Runnable::run, LoggerFactory.getLogger(getClass()));
    }

    @Test void claimsReleasesAndFindsNicknamesCaseInsensitively() throws Exception {
        try (Database database = database()) {
            database.migrate("nick", NickStore.MIGRATIONS);
            NickStore store = new NickStore(database);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();

            assertEquals(NickStore.ClaimResult.CLAIMED,
                    store.claim(alice, NickState.of("Alice", "Alice")).join());
            assertEquals(alice, store.findOwner("alice").join().orElseThrow());

            assertEquals(NickStore.ClaimResult.TAKEN,
                    store.claim(bob, NickState.of("ALICE", "ALICE")).join());
            assertNull(store.component.loader().load(bob).join());

            assertEquals(NickStore.ClaimResult.CLAIMED,
                    store.claim(bob, NickState.of("Bob", "Bob")).join());
            store.release(alice).join();
            assertTrue(store.findOwner("alice").join().isEmpty());
            assertEquals(NickStore.ClaimResult.CLAIMED,
                    store.claim(bob, NickState.of("Alice", "Alice")).join());
            assertEquals(bob, store.findOwner("alice").join().orElseThrow());
        }
    }

    @Test void componentRoundTripsAndClears() throws Exception {
        try (Database database = database()) {
            database.migrate("nick", NickStore.MIGRATIONS);
            NickStore store = new NickStore(database);
            UUID uuid = UUID.randomUUID();
            NickState state = NickState.of("<red>Bob</red>", "Bob");

            store.component.saver().save(uuid, state).join();
            assertEquals(state, store.component.loader().load(uuid).join());

            store.component.saver().save(uuid, NickState.EMPTY).join();
            assertNull(store.component.loader().load(uuid).join());
        }
    }

    @Test void migrationIsIdempotent() throws Exception {
        try (Database database = database()) {
            database.migrate("nick", NickStore.MIGRATIONS);
            database.migrate("nick", NickStore.MIGRATIONS);
            NickStore store = new NickStore(database);
            UUID uuid = UUID.randomUUID();
            assertEquals(NickStore.ClaimResult.CLAIMED,
                    store.claim(uuid, NickState.of("Bob", "Bob")).join());
        }
    }

    @Test void failedClaimRestoresThePreviousNickname() throws Exception {
        try (Database database = database()) {
            database.migrate("nick", NickStore.MIGRATIONS);
            NickStore store = new NickStore(database);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            store.claim(alice, NickState.of("Alice", "Alice")).join();
            store.claim(bob, NickState.of("Robert", "Robert")).join();

            assertEquals(NickStore.ClaimResult.TAKEN,
                    store.claim(bob, NickState.of("Alice", "Alice")).join());
            NickState still = store.component.loader().load(bob).join();
            assertEquals("Robert", still.nick());
            assertEquals("Robert", still.plain());
            assertEquals(alice, store.findOwner("alice").join().orElseThrow());
        }
    }
}
