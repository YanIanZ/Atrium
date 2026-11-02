package dev.yanianz.atrium.module.economy;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.api.economy.TransactionResult;
import dev.yanianz.atrium.api.economy.TransactionResult.Status;
import dev.yanianz.atrium.module.economy.EconomyStore.Key;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The native Vault v2 provider's semantics against an in-memory backend: exact decimals, groups, ledger reasons. */
@SuppressWarnings("deprecation")
class VaultUnlockedBridgeTest {
    private static final String DEFAULT = WorldGroups.DEFAULT_KEY;
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private FakeBackend backend;
    private VaultUnlockedBridge economy;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @BeforeEach void setUp() {
        Map<String, List<String>> worlds = new LinkedHashMap<>();
        worlds.put("survival", List.of("world", "world_nether"));
        worlds.put("resource", List.of("resource_*"));
        backend = new FakeBackend(new WorldGroups(true, "default", worlds, false));
        backend.balances.put(new Key(alice, DEFAULT), d("100.00"));
        backend.balances.put(new Key(alice, "survival"), d("50.00"));
        economy = new VaultUnlockedBridge(backend, () -> backend.unregistered.set(true));
    }

    @Test void readsAreExactAndUseTheGroupOfTheWorld() {
        assertEquals(d("100.00"), economy.balance("Shop", alice));
        assertEquals(d("50.00"), economy.balance("Shop", alice, "world_nether"));
        assertEquals(d("50.00"), economy.getBalance("Shop", alice, "survival"), "a group name selects that group");
        assertEquals(d("0.00"), economy.balance("Shop", alice, "resource_1"));
        assertEquals(d("100.00"), economy.balance("Shop", alice, "unknown_world"), "unmapped world: default group");
        assertEquals(d("100.00"), economy.balance("Shop", alice, "lobby", "coins"), "currency alias");
        assertEquals(BigDecimal.ZERO, economy.balance("Shop", alice, "lobby", "gems"), "unknown currency");
        assertTrue(economy.has("Shop", alice, d("100.00")));
        assertFalse(economy.has("Shop", alice, d("100.01")));
        assertFalse(economy.has("Shop", alice, d("-1")));
        assertFalse(economy.has("Shop", alice, null));
        assertEquals(0, backend.waits.get(), "reads never take the bounded write wait");
    }

    @Test void writesAreExactRecordThePluginAndNeverRound() {
        var response = economy.withdraw("Shop", alice, d("0.10"));
        assertEquals(ResponseType.SUCCESS, response.type);
        assertEquals(d("0.10"), response.amount);
        assertEquals(d("99.90"), response.balance);
        assertEquals("", response.errorMessage);
        assertEquals("vault2:withdraw:Shop", backend.reasons.getLast());

        var tooPrecise = economy.deposit("Shop", alice, d("0.001"));
        assertEquals(ResponseType.FAILURE, tooPrecise.type);
        assertTrue(tooPrecise.errorMessage.contains("decimal places"));
        assertEquals(ResponseType.SUCCESS, economy.deposit("Shop", alice, d("0.10000")).type, "trailing zeros are fine");
        assertEquals(ResponseType.FAILURE, economy.deposit("Shop", alice, d("-1")).type);
        assertEquals(ResponseType.FAILURE, economy.deposit("Shop", alice, null).type);
        assertEquals(ResponseType.FAILURE, economy.deposit("Shop", null, d("1")).type);
        assertEquals(ResponseType.FAILURE, economy.deposit("Shop", alice, "world", "gems", d("1")).type);
        assertEquals(2, backend.reasons.size(), "invalid calls never reach the ledger");

        economy.deposit("Bad\nPlugin;DROP TABLE", alice, "world", d("5"));
        assertEquals("vault2:deposit:BadPluginDROPTABLE", backend.reasons.getLast(), "untrusted plugin names are cleaned");
        assertEquals(d("55.00"), backend.balances.get(new Key(alice, "survival")));
        economy.deposit(null, alice, d("1"));
        assertEquals("vault2:deposit:unknown", backend.reasons.getLast());
    }

    @Test void failedAndTimedOutWritesAreFailures() {
        var insufficient = economy.withdraw("Shop", alice, d("1000"));
        assertEquals(ResponseType.FAILURE, insufficient.type);
        assertEquals("INSUFFICIENT_FUNDS", insufficient.errorMessage);
        assertEquals(d("100.00"), insufficient.balance);
        backend.timeout = true;
        var timedOut = economy.deposit("Shop", alice, d("1"));
        assertEquals(ResponseType.FAILURE, timedOut.type);
        assertTrue(timedOut.errorMessage.contains("unknown"));
    }

    @Test void setAndTransferAreSingleTransactionsInOneGroup() {
        assertTrue(economy.set("Shop", alice, "world", d("12.34")).transactionSuccess());
        assertEquals(d("12.34"), backend.balances.get(new Key(alice, "survival")));
        assertEquals("vault2:set:Shop", backend.reasons.getLast());

        var moved = economy.transfer("Shop", alice, bob, d("40"));
        assertEquals(ResponseType.SUCCESS, moved.type);
        assertEquals(d("60.00"), backend.balances.get(new Key(alice, DEFAULT)));
        assertEquals(d("40.00"), backend.balances.get(new Key(bob, DEFAULT)), "receiver in the payer's group");
        assertEquals(Optional.of(d("60.00")), moved.balance(alice));
        assertEquals(Optional.of(d("40.00")), moved.balance(bob));
        assertEquals(1, backend.transfers.get(), "one ledger transaction, not withdraw + deposit");

        assertEquals(ResponseType.FAILURE, economy.transfer("Shop", alice, alice, d("1")).type);
        assertEquals(ResponseType.FAILURE, economy.transfer("Shop", alice, bob, "world", d("13")).type, "survival has 12.34");
    }

    @Test void accountsAndMetadata() {
        assertEquals("Atrium", economy.getName());
        assertEquals(2, economy.fractionalDigits("Shop"));
        assertEquals("default", economy.getDefaultCurrency("Shop"));
        assertTrue(economy.hasCurrency("Coin"));
        assertFalse(economy.hasMultiCurrencySupport());
        assertTrue(economy.hasAccount(alice));
        assertFalse(economy.hasAccount(bob));
        assertTrue(economy.createAccount(bob, "bob", true));
        assertTrue(economy.hasAccount(bob));
        assertFalse(economy.createAccount(UUID.randomUUID(), "town", false), "no non-player accounts");
        assertFalse(economy.createSharedAccount("Shop", UUID.randomUUID(), "x", alice));
        assertFalse(economy.deleteAccount("Shop", alice));
        assertTrue(economy.async().isEmpty());
    }

    @Test void closeDisablesAndUnregisters() {
        economy.close();
        assertTrue(backend.unregistered.get());
        assertFalse(economy.isEnabled());
        assertEquals(ResponseType.FAILURE, economy.deposit("Shop", alice, d("1")).type);
        assertEquals(BigDecimal.ZERO, economy.balance("Shop", alice));
    }

    @Test void v2WorldParameterMapping() {
        Map<String, List<String>> worlds = new LinkedHashMap<>();
        worlds.put("survival", List.of("world"));
        worlds.put("resource", List.of("res_*", "survival"));
        WorldGroups groups = new WorldGroups(true, "main", worlds, false);
        assertEquals("survival", groups.keyOfWorldOrGroup("WORLD"), "world names first");
        assertEquals("resource", groups.keyOfWorldOrGroup("res_1"), "then globs");
        assertEquals("resource", groups.keyOfWorldOrGroup("survival"), "a world literally named like a group wins");
        assertEquals("resource", groups.keyOfWorldOrGroup("Resource"), "then group names");
        assertEquals(DEFAULT, groups.keyOfWorldOrGroup("main"), "the default group by its display name");
        assertEquals(DEFAULT, groups.keyOfWorldOrGroup("lobby"));
        assertEquals(DEFAULT, groups.keyOfWorldOrGroup(null));
        assertEquals(DEFAULT, WorldGroups.disabled().keyOfWorldOrGroup("survival"), "groups off: one balance");
    }

    @Test void ledgerReasonIsBounded() {
        String reason = VaultUnlockedBridge.reason("withdraw", "x".repeat(500));
        assertTrue(reason.length() <= 255);
        assertEquals("vault2:withdraw:" + "x".repeat(64), reason);
    }

    /** Storage stand-in with the economy's write semantics (scale 2, no negative balances). */
    private static final class FakeBackend implements VaultUnlockedBridge.Backend {
        final WorldGroups groups;
        final Map<Key, BigDecimal> balances = new ConcurrentHashMap<>();
        final List<String> reasons = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger waits = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger transfers = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicBoolean unregistered = new AtomicBoolean();
        volatile boolean timeout;

        FakeBackend(WorldGroups groups) { this.groups = groups; }

        private BigDecimal get(Key key) { return balances.getOrDefault(key, d("0.00")); }

        private CompletableFuture<TransactionResult> apply(Key key, BigDecimal next, BigDecimal amount, String reason) {
            reasons.add(reason);
            BigDecimal old = get(key);
            if (next.signum() < 0) return CompletableFuture.completedFuture(new TransactionResult(Status.INSUFFICIENT_FUNDS, amount, old));
            balances.put(key, next.setScale(2));
            return CompletableFuture.completedFuture(new TransactionResult(Status.SUCCESS, amount, next.setScale(2)));
        }

        @Override public Key key(UUID id, String world) {
            return new Key(id, world == null ? DEFAULT : groups.keyOfWorldOrGroup(world));
        }
        @Override public BigDecimal read(Key key) { return get(key); }
        @Override public boolean isOnline(UUID id) { return false; }
        @Override public CompletableFuture<Boolean> exists(Key key) { return CompletableFuture.completedFuture(balances.containsKey(key)); }
        @Override public CompletableFuture<BigDecimal> ensure(Key key) {
            return CompletableFuture.completedFuture(balances.computeIfAbsent(key, k -> d("0.00")));
        }
        @Override public CompletableFuture<TransactionResult> deposit(Key key, BigDecimal amount, String reason) {
            return apply(key, get(key).add(amount), amount, reason);
        }
        @Override public CompletableFuture<TransactionResult> withdraw(Key key, BigDecimal amount, String reason) {
            return apply(key, get(key).subtract(amount), amount, reason);
        }
        @Override public CompletableFuture<TransactionResult> set(Key key, BigDecimal amount, String reason) {
            return apply(key, amount, amount, reason);
        }
        @Override public CompletableFuture<TransactionResult> transfer(Key from, Key to, BigDecimal amount, String reason) {
            transfers.incrementAndGet();
            reasons.add(reason);
            BigDecimal after = get(from).subtract(amount);
            if (after.signum() < 0) {
                return CompletableFuture.completedFuture(new TransactionResult(Status.INSUFFICIENT_FUNDS, amount, get(from)));
            }
            balances.put(from, after.setScale(2));
            balances.put(to, get(to).add(amount).setScale(2));
            return CompletableFuture.completedFuture(new TransactionResult(Status.SUCCESS, amount, after.setScale(2)));
        }
        @Override public BigDecimal cached(Key key) { return balances.get(key); }
        @Override public <T> T await(CompletableFuture<T> future) {
            waits.incrementAndGet();
            if (timeout) throw new IllegalStateException("Storage error or timeout; outcome unknown");
            return future.join();
        }
        @Override public int scale() { return 2; }
        @Override public String format(BigDecimal amount) { return "$" + amount.toPlainString(); }
        @Override public String singular() { return "Coin"; }
        @Override public String plural() { return "Coins"; }
        @Override public Optional<String> name(UUID id) { return Optional.empty(); }
        @Override public Map<UUID, String> online() { return Map.of(); }
    }
}
