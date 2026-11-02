package dev.yanianz.atrium.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class GuardedEconomyTest {
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final List<AuditEntry> audited = new CopyOnWriteArrayList<>();
    private final List<Economy> registered = new ArrayList<>();
    private MapEconomy real;
    private GuardedEconomy guard;

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        real = new MapEconomy("Real");
        real.balances.put("alice", 100.0);
        guard = new GuardedEconomy(() -> List.copyOf(registered), new GuardSettings(1_000_000),
                new RateLimitedLog(logger, 60_000), audited::add, () -> "TestPlugin");
        registered.add(guard); // the guard itself is registered first (highest priority)
        registered.add(real);
    }

    @Test
    void rejectsNaNNegativeInfiniteAndTooLarge() {
        for (double bad : new double[] {Double.NaN, -0.01, -1, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1_000_000.01}) {
            EconomyResponse withdraw = guard.withdrawPlayer("alice", bad);
            EconomyResponse deposit = guard.depositPlayer("alice", bad);
            assertEquals(ResponseType.FAILURE, withdraw.type, "withdraw " + bad);
            assertEquals(ResponseType.FAILURE, deposit.type, "deposit " + bad);
            assertNotNull(withdraw.errorMessage);
        }
        assertEquals(0, real.writes.get(), "nothing reaches the provider");
        assertEquals(100.0, real.balances.get("alice"));
        assertEquals(12, guard.rejectedCount());
        assertEquals("REJECTED", audited.getFirst().result());
        assertEquals("TestPlugin", audited.getFirst().caller());
        assertFalse(logged.isEmpty(), "rejections are logged, not swallowed");
    }

    @Test
    void maxAmountIsInclusiveAndZeroIsAllowed() {
        assertTrue(guard.depositPlayer("alice", 1_000_000).transactionSuccess());
        assertTrue(guard.depositPlayer("alice", 0).transactionSuccess());
        assertEquals(1_000_100.0, real.balances.get("alice"));
    }

    @Test
    void readsValidateToo() {
        assertFalse(guard.has("alice", Double.NaN));
        assertFalse(guard.has("alice", -5));
        assertTrue(guard.has("alice", 50));
        assertEquals(0.0, guard.getBalance((String) null));
        assertFalse(guard.hasAccount((String) null));
        assertFalse(guard.hasAccount(" "));
        assertEquals(ResponseType.FAILURE, guard.withdrawPlayer((org.bukkit.OfflinePlayer) null, 1).type);
        assertEquals(ResponseType.FAILURE, guard.depositPlayer((String) null, 1).type);
    }

    @Test
    void validWritesAreForwardedAndAudited() {
        EconomyResponse response = guard.withdrawPlayer("alice", 30);
        assertTrue(response.transactionSuccess());
        assertEquals(70.0, response.balance);
        assertEquals(70.0, guard.getBalance("alice"));
        AuditEntry entry = audited.getLast();
        assertEquals("withdrawPlayer", entry.operation());
        assertEquals("alice", entry.account());
        assertEquals("SUCCESS", entry.result());
        assertEquals(30.0, entry.amount());
    }

    @Test
    void offlinePlayerOverloadsAreForwarded() {
        var bob = Players.offline("bob");
        assertTrue(guard.depositPlayer(bob, 5).transactionSuccess());
        assertEquals(5.0, guard.getBalance(bob));
    }

    @Test
    void delegateExcludesSelfAndOtherGuards() {
        GuardedEconomy other = new GuardedEconomy(List::of, GuardSettings.defaults(),
                new RateLimitedLog(Logger.getAnonymousLogger(), 0), AuditSink.NONE, CallerResolver.NONE);
        registered.add(1, other);
        assertSame(real, guard.delegate());
        assertEquals("Real", guard.getName());
    }

    @Test
    void delegateIsCachedUntilInvalidated() {
        assertSame(real, guard.delegate());
        MapEconomy better = new MapEconomy("Better");
        registered.add(1, better);
        assertSame(real, guard.delegate(), "cached");
        guard.invalidate();
        assertSame(better, guard.delegate());
        registered.remove(better);
        registered.remove(real);
        guard.invalidate();
        assertNull(guard.delegate());
    }

    @Test
    void noDelegateBehavesLikeNoEconomy() {
        registered.remove(real);
        guard.invalidate();
        assertFalse(guard.isEnabled());
        EconomyResponse response = guard.depositPlayer("alice", 1);
        assertEquals(ResponseType.FAILURE, response.type);
        assertEquals(GuardedEconomy.NO_PROVIDER, response.errorMessage);
        assertEquals(0.0, guard.getBalance("alice"));
        assertFalse(guard.has("alice", 0));
        assertEquals(List.of(), guard.getBanks());
        assertEquals("None", guard.getName());
    }

    @Test
    void delegateExceptionBecomesFailureAndIsLogged() {
        real.failWith = new IllegalStateException("database down");
        EconomyResponse response = guard.withdrawPlayer("alice", 1);
        assertEquals(ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.contains("IllegalStateException"));
        assertFalse(guard.has("alice", 1));
        assertEquals(0.0, guard.getBalance("alice"));
        assertFalse(guard.isEnabled());
        assertEquals("1.00", guard.format(1), "format falls back");
        assertEquals(5, guard.failureCount());
        List<LogRecord> severe = logged.stream().filter(r -> r.getLevel() == Level.SEVERE).toList();
        assertFalse(severe.isEmpty());
        assertNotNull(severe.getFirst().getThrown(), "stack trace kept");
        assertEquals("FAILURE", audited.getLast().result());
    }

    @Test
    void repeatedErrorsAreRateLimited() {
        real.failWith = new IllegalStateException("down");
        for (int i = 0; i < 100; i++) guard.withdrawPlayer("alice", 1);
        long severe = logged.stream().filter(r -> r.getLevel() == Level.SEVERE).count();
        assertEquals(1, severe);
        assertEquals(100, guard.failureCount());
    }

    @Test
    void nullResponseFromProviderBecomesFailure() {
        MapEconomy broken = new MapEconomy("Broken") {
            @Override public EconomyResponse depositPlayer(String player, double amount) { return null; }
        };
        registered.add(1, broken);
        guard.invalidate();
        assertEquals(ResponseType.FAILURE, guard.depositPlayer("alice", 1).type);
    }

    @Test
    void settingsRejectInvalidMaximum() {
        assertThrows(IllegalArgumentException.class, () -> new GuardSettings(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new GuardSettings(0));
        assertThrows(IllegalArgumentException.class, () -> new GuardSettings(Double.POSITIVE_INFINITY));
    }
}
