package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.*;
import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.core.config.CoreConfig;
import dev.yanianz.atrium.core.storage.Database;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** World-group balances: isolation, migration of old data, Vault world mapping, warm-up and the disabled mode. */
class EconomyGroupsTest {
    private static final String DEFAULT = WorldGroups.DEFAULT_KEY;
    @TempDir Path folder;
    ExecutorService executor;
    Database db;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    @BeforeEach void setup() {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        db = new Database(new CoreConfig.Database(), folder, executor, LoggerFactory.getLogger("economy-test"));
    }

    @AfterEach void close() { db.close(); executor.shutdown(); }

    private static EconomyConfig config(boolean enabled, boolean cross) {
        EconomyConfig config = new EconomyConfig();
        config.groups.enabled = enabled;
        config.groups.allowCrossGroupPay = cross;
        config.groups.worldGroups = new LinkedHashMap<>();
        config.groups.worldGroups.put("survival", List.of("world", "world_nether"));
        config.groups.worldGroups.put("resource", List.of("resource_*"));
        return config;
    }

    private EconomyStore store(boolean enabled) throws Exception {
        db.migrate("users", dev.yanianz.atrium.core.user.UserManager.MIGRATIONS);
        db.migrate("economy", EconomyStore.MIGRATIONS);
        return new EconomyStore(db, config(enabled, false), LoggerFactory.getLogger("economy-test"));
    }

    private static EconomyStore.Key key(UUID id, String group) { return new EconomyStore.Key(id, group); }

    private static BigDecimal money(String value) { return new BigDecimal(value); }

    @Test void groupsAreIsolatedInStorageAndTransactions() throws Exception {
        EconomyStore store = store(true);
        assertTrue(store.set(a, "survival", money("50"), "seed").join().success());
        assertTrue(store.set(a, "resource", money("7"), "seed").join().success());
        assertEquals(money("50.00"), store.balance(a, "survival").join());
        assertEquals(money("7.00"), store.balance(a, "resource").join());
        assertEquals(money("0.00"), store.balance(a, "default").join(), "The default group is untouched");
        assertEquals(money("0.00"), store.balance(a).join(), "Offline: the plain API reads the default group");

        // A withdrawal in one group cannot spend money of another group.
        assertEquals(TransactionResult.Status.INSUFFICIENT_FUNDS, store.withdraw(a, "resource", money("8"), "x").join().status());
        assertTrue(store.transfer(a, b, "survival", money("20"), "pay").join().success());
        assertEquals(money("30.00"), store.balance(a, "survival").join());
        assertEquals(money("20.00"), store.balance(b, "survival").join());
        assertEquals(money("0.00"), store.balance(b, "default").join());
        assertFalse(store.hasAccount(b, "resource").join());
        assertTrue(store.hasAccount(b, "survival").join());

        // The ledger records the group of every row.
        var survival = store.history(a, "survival").join();
        assertFalse(survival.isEmpty());
        assertTrue(survival.stream().allMatch(entry -> entry.group().equals("survival")));
        assertTrue(store.history(a).join().stream().anyMatch(entry -> entry.group().equals("resource")));

        var boards = store.leaderboards(Set.of(), 0).join();
        assertEquals(a, boards.get("survival").getFirst().uuid());
        assertEquals(1, boards.get("resource").size());
        assertNull(boards.get(DEFAULT), "Nobody has a default-group account yet");
    }

    @Test void unknownGroupsAreRejectedWithoutCreatingAccounts() throws Exception {
        EconomyStore store = store(true);
        assertEquals(TransactionResult.Status.NO_ACCOUNT, store.deposit(a, "nope", BigDecimal.ONE, "x").join().status());
        var failure = assertThrows(CompletionException.class, () -> store.balance(a, "nope").join());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertNull(store.cachedBalance(a, "nope"));
        assertFalse(store.hasAccount(a, "nope").join());
        assertTrue(store.storedGroups().join().isEmpty());
    }

    @Test void crossGroupTransferMovesMoneyBetweenExactlyTheGivenGroups() throws Exception {
        EconomyStore store = store(true);
        store.set(a, "survival", money("10"), "seed").join();
        assertTrue(store.transferTaxed(key(a, "survival"), key(b, DEFAULT), money("4"), money("1"), "pay").join().success());
        assertEquals(money("5.00"), store.balance(a, "survival").join());
        assertEquals(money("4.00"), store.balance(b, "default").join());
        assertEquals(money("0.00"), store.balance(b, "survival").join());
        assertFalse(store.transferTaxed(key(a, "survival"), key(a, DEFAULT), BigDecimal.ONE, BigDecimal.ZERO, "self")
                .join().success(), "Moving money between one's own groups is a self payment");
    }

    @Test void crossGroupPayIsRefusedUnlessAllowed() throws Exception {
        db.migrate("economy", EconomyStore.MIGRATIONS);
        EconomyStore strict = new EconomyStore(db, config(true, false), LoggerFactory.getLogger("economy-test"));
        strict.online(b, "resource_1").join();
        String to = WorldGroups.receiverGroup("survival", null, strict.isOnline(b) ? strict.contextGroup(b) : null);
        assertEquals("resource", to);
        assertFalse(strict.worldGroups().permits("survival", to), "Payer in survival, receiver online in resource");
        String offline = WorldGroups.receiverGroup("survival", null, strict.isOnline(a) ? strict.contextGroup(a) : null);
        assertTrue(strict.worldGroups().permits("survival", offline), "An offline receiver is paid in the payer's group");
        EconomyStore lenient = new EconomyStore(db, config(true, true), LoggerFactory.getLogger("economy-test"));
        assertTrue(lenient.worldGroups().permits("survival", "resource"));
    }

    @Test void migrationKeepsOldBalancesInTheDefaultGroup() throws Exception {
        db.migrate("economy", EconomyStore.MIGRATIONS.subList(0, 2));
        long now = System.currentTimeMillis();
        db.update("INSERT INTO {prefix}economy_accounts (uuid, balance, updated_at) VALUES (?, ?, ?)", a.toString(), "123.45", now).join();
        db.update("INSERT INTO {prefix}economy_transactions (id, account, counterparty, amount, balance_after, reason, created)"
                + " VALUES (?, ?, NULL, ?, ?, ?, ?)", UUID.randomUUID().toString(), a.toString(), "123.45", "123.45", "seed", now).join();
        db.update("INSERT INTO {prefix}economy_cheques (id, value, creator, created) VALUES (?, ?, ?, ?)",
                "old-note", "5", a.toString(), now).join();
        db.migrate("economy", EconomyStore.MIGRATIONS);

        EconomyStore enabled = new EconomyStore(db, config(true, false), LoggerFactory.getLogger("economy-test"));
        assertEquals(money("123.45"), enabled.balance(a, "default").join(), "Old money is in the default group");
        assertEquals(money("123.45"), enabled.balance(a).join());
        assertEquals(money("0.00"), enabled.balance(a, "survival").join());
        assertEquals(Map.of(DEFAULT, 1L), enabled.storedGroups().join());
        assertEquals(DEFAULT, enabled.history(a).join().getFirst().group());
        assertEquals(EconomyStore.Claim.WRONG_GROUP, enabled.claimCheque("old-note", b, "survival").join().status(),
                "An old cheque belongs to the default group");
        assertEquals(EconomyStore.Claim.OK, enabled.claimCheque("old-note", b, DEFAULT).join().status());

        // A renamed default group still owns the old money.
        EconomyConfig renamed = config(true, false);
        renamed.groups.defaultGroup = "main";
        EconomyStore main = new EconomyStore(db, renamed, LoggerFactory.getLogger("economy-test"));
        assertEquals(money("123.45"), main.balance(a, "main").join());
        assertEquals("main", main.defaultGroup());

        // The new schema still accepts writes for migrated rows (composite key, exact CAS).
        assertTrue(enabled.withdraw(a, money("0.45"), "spend").join().success());
        assertEquals(money("123.00"), enabled.balance(a, "default").join());
    }

    @Test void vaultWorldsMapToGroupsAndWorldlessCallsUseTheCurrentGroup() throws Exception {
        EconomyStore store = store(true);
        store.set(a, "survival", money("5"), "seed").join();
        store.set(a, "resource", money("9"), "seed").join();
        store.set(a, "default", money("2"), "seed").join();
        assertEquals(key(a, "survival"), store.keyFor(a, "world_nether"));
        assertEquals(key(a, "resource"), store.keyFor(a, "resource_7"));
        assertEquals(key(a, DEFAULT), store.keyFor(a, "lobby"), "Unlisted world: default group");
        assertEquals(key(a, DEFAULT), store.keyFor(a, null), "World-less and offline: default group");

        VaultReads reads = new VaultReads(new VaultReads.Store() {
            @Override public BigDecimal cachedRead(EconomyStore.Key account) { return store.cachedRead(account); }
            @Override public boolean isOnline(EconomyStore.Key account) { return store.isOnline(account.id()); }
            @Override public java.util.concurrent.CompletableFuture<BigDecimal> balance(EconomyStore.Key account) { return store.balance(account); }
            @Override public void rememberRead(EconomyStore.Key account, BigDecimal value) { store.rememberRead(account, value); }
        }, LoggerFactory.getLogger("economy-test"), 5000, () -> false, () -> true);
        assertEquals(money("5.00"), reads.balance(store.keyFor(a, "world")));
        assertEquals(money("9.00"), reads.balance(store.keyFor(a, "resource_1")));
        assertEquals(money("2.00"), reads.balance(store.keyFor(a, null)));

        store.online(a, "resource_1").join();
        assertEquals(key(a, "resource"), store.keyFor(a, null), "Online: world-less calls use the current group");
        assertEquals(money("9.00"), store.cachedBalance(a));
        assertTrue(store.deposit(a, BigDecimal.ONE, "vault:deposit").join().success());
        assertEquals(money("10.00"), store.balance(a, "resource").join(), "A world-less write lands in the current group");
        // Online reads in any group come from the cache, even on a tick thread.
        VaultReads tick = new VaultReads(new VaultReads.Store() {
            @Override public BigDecimal cachedRead(EconomyStore.Key account) { return store.cachedRead(account); }
            @Override public boolean isOnline(EconomyStore.Key account) { return store.isOnline(account.id()); }
            @Override public java.util.concurrent.CompletableFuture<BigDecimal> balance(EconomyStore.Key account) {
                throw new AssertionError("An online read must not touch storage");
            }
            @Override public void rememberRead(EconomyStore.Key account, BigDecimal value) { }
        }, LoggerFactory.getLogger("economy-test"), 5000, () -> true, () -> true);
        assertEquals(money("5.00"), tick.balance(store.keyFor(a, "world")));
        assertEquals(money("2.00"), tick.balance(store.keyFor(a, "lobby")));
    }

    @Test void preLoginWarmLoadsEveryGroupAndWorldChangesNeedNoStorage() throws Exception {
        EconomyStore store = store(true);
        store.set(a, "survival", money("3"), "seed").join();
        store.set(a, "default", money("4"), "seed").join();
        store.warm(a).join();
        assertEquals(money("3.00"), store.cachedRead(key(a, "survival")), "The warm loads every group before login");
        assertEquals(money("4.00"), store.cachedRead(key(a, DEFAULT)));
        assertEquals(money("0.00"), store.cachedRead(key(a, "resource")), "A group without a row reads the starting balance");
        assertFalse(store.isOnline(a));
        assertFalse(store.exists(key(a, "resource")).join(), "Warming never creates an account");

        store.online(a, "world").join();
        assertTrue(store.exists(key(a, "survival")).join());
        assertEquals("survival", store.currentGroup(a));
        assertEquals(money("3.00"), store.cachedBalance(a));
        for (String group : List.of("default", "survival", "resource")) {
            assertNotNull(store.cachedBalance(a, group), "Every group is cached while online: " + group);
        }
        store.moved(a, "lobby");
        assertEquals("default", store.currentGroup(a));
        assertEquals(money("4.00"), store.cachedBalance(a));

        store.offline(a).join();
        assertEquals("default", store.currentGroup(a), "Offline players use the default group");
        for (String group : List.of("default", "survival", "resource")) {
            assertNull(store.cachedRead(key(a, group)), "Quit evicts every group: " + group);
        }
        store.moved(a, "world");
        assertEquals("default", store.currentGroup(a), "A world change of an offline player is ignored");
    }

    @Test void disabledGroupsKeepTheExactOldBehaviour() throws Exception {
        EconomyStore store = store(false);
        assertFalse(store.groupsEnabled());
        assertEquals(List.of("default"), store.groups());
        assertEquals("default", store.groupOfWorld("resource_1"));
        store.online(a, "resource_1").join();
        assertEquals("default", store.currentGroup(a));
        assertEquals(key(a, DEFAULT), store.keyFor(a, "world"), "Vault world overloads share the one balance");
        assertTrue(store.deposit(a, money("10"), "give").join().success());
        assertTrue(store.transfer(a, b, money("4"), "pay").join().success());
        assertEquals(money("6.00"), store.cachedBalance(a));
        assertEquals(money("6.00"), store.balance(a).join());
        assertEquals(money("4.00"), store.balance(b).join());
        assertEquals(store.balance(b).join(), store.balance(b, "default").join());
        assertEquals(TransactionResult.Status.NO_ACCOUNT, store.deposit(a, "survival", BigDecimal.ONE, "x").join().status(),
                "Configured groups are inert while disabled");
        store.moved(a, "world");
        assertEquals(money("6.00"), store.cachedBalance(a));
        assertEquals(Set.of(DEFAULT), store.leaderboards(Set.of(), 0).join().keySet());
        assertEquals(Map.of(DEFAULT, 2L), store.storedGroups().join());
        assertEquals(EconomyStore.Claim.MISSING, store.claimCheque("none", a).join().status());
        assertTrue(store.history(a).join().stream().allMatch(entry -> entry.group().equals(DEFAULT)));
    }
}
