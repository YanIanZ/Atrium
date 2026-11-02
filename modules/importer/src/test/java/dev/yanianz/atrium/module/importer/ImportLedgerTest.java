package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** The ledger is what makes a repeated import a no-op: one marker per source, category and key. */
class ImportLedgerTest {
    @TempDir Path folder;
    Database db;
    ExecutorService executor;
    ImportLedger ledger;

    @BeforeEach
    void setup() throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("importer-test"));
        db.migrate("importer", ImportLedger.MIGRATIONS);
        ledger = new ImportLedger(db);
    }

    @AfterEach
    void close() {
        db.close();
        executor.close();
    }

    @Test
    void markingTwiceKeepsOneMarker() {
        UUID player = UUID.randomUUID();
        ledger.mark("essentials", ImportCategory.MAIL, player).join();
        ledger.mark("essentials", ImportCategory.MAIL, player).join();
        assertEquals(Set.of(player), ledger.load("essentials", ImportCategory.MAIL).join());
    }

    @Test
    void markersAreSeparatedBySourceAndCategory() {
        UUID player = UUID.randomUUID();
        ledger.mark("cmi", ImportCategory.LOCATIONS, player).join();
        ledger.mark(BalanceSource.ledgerSource("XConomy"), ImportCategory.ECONOMY, player).join();

        assertEquals(Set.of(player), ledger.load("cmi", ImportCategory.LOCATIONS).join());
        assertTrue(ledger.load("essentials", ImportCategory.LOCATIONS).join().isEmpty());
        assertTrue(ledger.load("cmi", ImportCategory.ECONOMY).join().isEmpty());
        assertEquals(Set.of(player), ledger.load("vault:XConomy", ImportCategory.ECONOMY).join());
        assertTrue(ledger.load("vault:RoyaleEconomy", ImportCategory.ECONOMY).join().isEmpty());
    }

    @Test
    void punishmentMarkersSurviveARerunAndCanBeUndone() {
        UUID target = UUID.randomUUID();
        UUID ban = ExtendedImportJob.punishmentKey(dev.yanianz.atrium.api.moderation.PunishmentType.TEMPBAN, target, null);
        UUID ipBan = ExtendedImportJob.punishmentKey(dev.yanianz.atrium.api.moderation.PunishmentType.IPBAN, null, "10.0.0.1");
        ledger.mark("vanilla", ImportCategory.PUNISHMENTS, ban).join();
        ledger.mark("vanilla", ImportCategory.PUNISHMENTS, ipBan).join();
        assertEquals(Set.of(ban, ipBan), ledger.load("vanilla", ImportCategory.PUNISHMENTS).join());

        // A failed write removes its marker again so the next run retries it.
        ledger.unmark("vanilla", ImportCategory.PUNISHMENTS, ban).join();
        assertEquals(Set.of(ipBan), ledger.load("vanilla", ImportCategory.PUNISHMENTS).join());
    }
}
