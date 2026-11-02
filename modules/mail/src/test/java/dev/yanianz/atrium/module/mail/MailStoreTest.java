package dev.yanianz.atrium.module.mail;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

class MailStoreTest {
    @TempDir Path directory;

    private Database open() {
        return new Database(new CoreConfig.Database(), directory, Runnable::run,
                LoggerFactory.getLogger(getClass()));
    }

    @Test void storesPagesMarksReadAndClears() throws Exception {
        try (Database db = open()) {
            db.migrate("mail", MailStore.MIGRATIONS);
            MailStore store = new MailStore(db);
            UUID recipient = UUID.randomUUID();
            UUID sender = UUID.randomUUID();
            long now = System.currentTimeMillis();

            store.insertAll(List.of(
                    new MailStore.Draft(recipient, sender, "Alice", "first", now - 3_000, 0),
                    new MailStore.Draft(recipient, null, "Console", "second", now - 2_000, now + 60_000),
                    new MailStore.Draft(recipient, sender, "Alice", "third", now - 1_000, 0)), 10).join();

            MailStore.Page first = store.readPage(recipient, 1, 2).join();
            assertEquals(1, first.page());
            assertEquals(2, first.pages());
            assertEquals(3, first.total());
            assertEquals("third", first.entries().getFirst().body());
            assertEquals("second", first.entries().get(1).body());
            assertFalse(first.entries().getFirst().read());
            assertEquals(1, first.unread());

            MailStore.Page second = store.readPage(recipient, 99, 2).join();
            assertEquals(2, second.page());
            assertEquals(1, second.entries().size());
            assertEquals("first", second.entries().getFirst().body());
            assertEquals(0, second.unread());

            MailStore.Page reread = store.readPage(recipient, 1, 2).join();
            assertTrue(reread.entries().stream().allMatch(MailStore.Entry::read));
            assertEquals(0, store.unread(recipient, now).join());

            assertFalse(store.clearOne(UUID.randomUUID(), reread.entries().getFirst().id()).join());
            assertTrue(store.clearOne(recipient, reread.entries().getFirst().id()).join());
            assertEquals(2, store.clearAll(recipient).join());
            assertEquals(0, store.readPage(recipient, 1, 5).join().total());
        }
    }

    @Test void refusesMailWhenMailboxIsFullAndSkipsExpired() throws Exception {
        try (Database db = open()) {
            db.migrate("mail", MailStore.MIGRATIONS);
            MailStore store = new MailStore(db);
            UUID recipient = UUID.randomUUID();
            UUID sender = UUID.randomUUID();
            long now = System.currentTimeMillis();

            store.insertAll(List.of(
                    new MailStore.Draft(recipient, sender, "Alice", "old", now, 0),
                    new MailStore.Draft(recipient, sender, "Alice", "expired", now, now - 1)), 2).join();
            List<UUID> delivered = store.insertAll(List.of(
                    new MailStore.Draft(recipient, sender, "Alice", "second", now, 0)), 2).join();
            assertEquals(List.of(recipient), delivered);

            List<UUID> refused = store.insertAll(List.of(
                    new MailStore.Draft(recipient, sender, "Alice", "third", now, 0)), 2).join();
            assertTrue(refused.isEmpty());
            assertEquals(2, store.readPage(recipient, 1, 5).join().total());
        }
    }

    @Test void droppedMailIsStoredButHiddenAndTakesNoMailboxSlot() throws Exception {
        try (Database db = open()) {
            db.migrate("mail", MailStore.MIGRATIONS);
            MailStore store = new MailStore(db);
            UUID recipient = UUID.randomUUID();
            UUID sender = UUID.randomUUID();
            long now = System.currentTimeMillis();

            List<UUID> hidden = store.insertAll(List.of(
                    new MailStore.Draft(recipient, sender, "Alice", "ignored", now, 0, true)), 1).join();
            assertEquals(List.of(recipient), hidden);
            assertEquals(0, store.readPage(recipient, 1, 5).join().total());
            assertEquals(0, store.readPage(recipient, 1, 5).join().unread());
            assertEquals(0, store.unread(recipient, now).join());
            assertFalse(store.clearOne(recipient, 1).join(), "a dropped row is not visible");

            // The dropped row must not consume the one visible slot.
            List<UUID> visible = store.insertAll(List.of(
                    new MailStore.Draft(recipient, sender, "Alice", "visible", now, 0)), 1).join();
            assertEquals(List.of(recipient), visible);
            MailStore.Page page = store.readPage(recipient, 1, 5).join();
            assertEquals(1, page.total());
            assertEquals("visible", page.entries().getFirst().body());

            // Two rows physically exist: the visible one and the dropped one.
            assertEquals(2, store.clearAll(recipient).join());
        }
    }

    @Test void migratesIdempotentlyAndPurgesExpiredRows() throws Exception {
        try (Database db = open()) {
            db.migrate("mail", MailStore.MIGRATIONS);
            db.migrate("mail", MailStore.MIGRATIONS);
            MailStore store = new MailStore(db);
            UUID recipient = UUID.randomUUID();
            long now = System.currentTimeMillis();
            store.insertAll(List.of(
                    new MailStore.Draft(recipient, null, "Console", "temporary", now, now + 50)), 5).join();

            assertEquals(0, store.unread(recipient, now + 100).join());
            assertEquals(1, store.purgeExpired(now + 100).join());
            assertEquals(0, store.readPage(recipient, 1, 5).join().total());
        }
    }
}
