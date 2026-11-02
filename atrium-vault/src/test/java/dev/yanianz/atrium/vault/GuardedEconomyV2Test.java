package dev.yanianz.atrium.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import net.milkbowl.vault2.economy.MultiEconomyResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class GuardedEconomyV2Test {
    private static final String PLUGIN = "Shop";
    private final List<LogRecord> logged = new CopyOnWriteArrayList<>();
    private final List<AuditEntry> audited = new CopyOnWriteArrayList<>();
    private final List<Economy> registered = new ArrayList<>();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private MapEconomyV2 real;
    private GuardedEconomyV2 guard;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @BeforeEach
    void setUp() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        real = new MapEconomyV2("Real");
        real.balances.put(alice, d("100.00"));
        guard = new GuardedEconomyV2(() -> List.copyOf(registered), new DecimalLimits(d("1000000"), 10, 38),
                new RateLimitedLog(logger, 60_000), audited::add, () -> "TestPlugin");
        registered.add(guard);
        registered.add(real);
    }

    @Test
    void rejectsInvalidAmountsOnEveryWrite() {
        List<BigDecimal> bad = new ArrayList<>(List.of(d("-0.01"), d("1000000.01"), d("0.00000000001"),
                d("1E-11"), new BigDecimal("1".repeat(39))));
        bad.add(null);
        for (BigDecimal amount : bad) {
            assertEquals(ResponseType.FAILURE, guard.withdraw(PLUGIN, alice, amount).type, "withdraw " + amount);
            assertEquals(ResponseType.FAILURE, guard.deposit(PLUGIN, alice, "world", amount).type, "deposit " + amount);
            assertEquals(ResponseType.FAILURE, guard.set(PLUGIN, alice, amount).type, "set " + amount);
            assertEquals(ResponseType.FAILURE, guard.transfer(PLUGIN, alice, bob, amount).type, "transfer " + amount);
            assertFalse(guard.has(PLUGIN, alice, amount), "has " + amount);
        }
        assertEquals(0, real.writes.get(), "nothing reaches the provider");
        assertEquals(d("100.00"), real.balances.get(alice));
        AuditEntry first = audited.getFirst();
        assertEquals("REJECTED", first.result());
        assertEquals("v2:withdraw", first.operation());
        assertEquals(d("-0.01"), first.amount());
        assertTrue(logged.stream().anyMatch(r -> r.getLevel() == Level.WARNING), "rejections are logged");
    }

    @Test
    void trailingZerosZeroAndTheMaximumAreAccepted() {
        assertTrue(guard.deposit(PLUGIN, alice, d("0.50000000000000000000")).transactionSuccess(), "scale 20, value 0.5");
        assertTrue(guard.deposit(PLUGIN, alice, BigDecimal.ZERO).transactionSuccess());
        assertTrue(guard.deposit(PLUGIN, alice, d("1000000")).transactionSuccess(), "max is inclusive");
        assertTrue(guard.deposit(PLUGIN, alice, d("1E+3")).transactionSuccess(), "negative scale");
        assertEquals(d("1001100.50"), real.balances.get(alice));
    }

    @Test
    void nullArgumentsAreRejectedNotForwarded() {
        assertEquals(ResponseType.FAILURE, guard.withdraw(null, alice, d("1")).type);
        assertEquals(ResponseType.FAILURE, guard.deposit(PLUGIN, null, d("1")).type);
        assertEquals(ResponseType.FAILURE, guard.deposit(PLUGIN, alice, null, d("1")).type);
        assertEquals(ResponseType.FAILURE, guard.withdraw(PLUGIN, alice, "world", null, d("1")).type);
        assertEquals(BigDecimal.ZERO, guard.balance(PLUGIN, null));
        assertFalse(guard.hasAccount(null));
        assertFalse(guard.createAccount(null, "x", true));
        assertEquals(0, real.writes.get());
        assertEquals(List.of(), real.plugins, "the provider was never called");
    }

    @Test
    void validWritesAreForwardedExactlyAndAudited() {
        EconomyResponse response = guard.withdraw(PLUGIN, alice, d("30.25"));
        assertTrue(response.transactionSuccess());
        assertEquals(d("69.75"), response.balance);
        assertEquals(d("69.75"), guard.balance(PLUGIN, alice));
        assertTrue(guard.has(PLUGIN, alice, d("69.75")));
        assertFalse(guard.has(PLUGIN, alice, d("69.76")));
        AuditEntry entry = audited.getLast();
        assertEquals("v2:withdraw", entry.operation());
        assertEquals(alice, entry.account());
        assertEquals("SUCCESS", entry.result());
        assertEquals(d("30.25"), entry.amount());
        assertEquals("TestPlugin (as Shop)", entry.caller(), "stack caller and declared plugin name");
        assertTrue(real.plugins.contains(PLUGIN), "the plugin name reaches the provider unchanged");
    }

    @Test
    void transferUsesTheProviderAndRefusesSelfTransfers() {
        MultiEconomyResponse ok = guard.transfer(PLUGIN, alice, bob, d("40"));
        assertEquals(ResponseType.SUCCESS, ok.type);
        assertEquals(d("60.00"), real.balances.get(alice));
        assertEquals(d("40.00"), real.balances.get(bob));
        assertEquals("v2:transfer", audited.getLast().operation());
        assertEquals(alice + "->" + bob, audited.getLast().account());
        assertEquals(ResponseType.FAILURE, guard.transfer(PLUGIN, alice, alice, d("1")).type);
    }

    @Test
    void delegateExcludesSelfAndOtherGuards() {
        GuardedEconomyV2 other = new GuardedEconomyV2(List::of, new DecimalLimits(d("1"), 2, 10),
                new RateLimitedLog(Logger.getAnonymousLogger(), 0), AuditSink.NONE, CallerResolver.NONE);
        registered.add(1, other);
        guard.invalidate();
        assertSame(real, guard.delegate());
        registered.remove(real);
        guard.invalidate();
        assertNull(guard.delegate(), "only guards left: no delegate, never itself");
        assertFalse(guard.isEnabled());
        assertEquals("None", guard.getName());
        assertEquals(GuardedEconomy.NO_PROVIDER, guard.deposit(PLUGIN, alice, d("1")).errorMessage);
    }

    @Test
    void providerExceptionsAndMissingMethodsBecomeResponses() {
        real.failWith = new IllegalStateException("database down");
        EconomyResponse response = guard.withdraw(PLUGIN, alice, d("1"));
        assertEquals(ResponseType.FAILURE, response.type);
        assertTrue(response.errorMessage.contains("IllegalStateException"));
        assertEquals(BigDecimal.ZERO, guard.balance(PLUGIN, alice));
        assertFalse(guard.has(PLUGIN, alice, d("1")));
        assertEquals(ResponseType.FAILURE, guard.transfer(PLUGIN, alice, bob, d("1")).type);
        assertEquals("FAILURE", audited.getLast().result());
        assertTrue(guard.failureCount() >= 4);

        MapEconomyV2 old = new MapEconomyV2("Old") {
            @Override public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
                throw new AbstractMethodError("compiled against an older API");
            }
        };
        registered.add(1, old);
        guard.invalidate();
        assertEquals(ResponseType.NOT_IMPLEMENTED, guard.deposit(PLUGIN, alice, d("1")).type);
    }

    @Test
    void nullResponseBecomesFailure() {
        MapEconomyV2 broken = new MapEconomyV2("Broken") {
            @Override public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) { return null; }
        };
        registered.add(1, broken);
        guard.invalidate();
        assertEquals(ResponseType.FAILURE, guard.deposit(PLUGIN, alice, d("1")).type);
    }

    @Test
    void asyncIsNotExposedSoEveryCallIsGuarded() {
        assertFalse(guard.supportsAsync());
        assertTrue(guard.async().isEmpty());
    }

    @Test
    void reportsSlowCallsOnlyOnTickThreads() {
        MapEconomyV2 slow = new MapEconomyV2("Slow") {
            @Override public BigDecimal getBalance(String pluginName, UUID accountID) {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return BigDecimal.ONE;
            }
        };
        registered.add(1, slow);
        guard.invalidate();
        guard.slowCallThresholdMillis(10);
        guard.tickThreadProbe(() -> false);
        guard.balance(PLUGIN, alice);
        assertEquals(0, guard.slowTickCallCount());
        guard.tickThreadProbe(() -> true);
        assertEquals(BigDecimal.ONE, guard.balance(PLUGIN, alice));
        assertEquals(1, guard.slowTickCallCount());
        assertTrue(logged.stream().anyMatch(r -> r.getMessage().contains("caller: TestPlugin")));
    }

    @Test
    void decimalLimitsValidateTheirOwnSettings() {
        assertThrows(IllegalArgumentException.class, () -> new DecimalLimits(BigDecimal.ZERO, 2, 10));
        assertThrows(IllegalArgumentException.class, () -> new DecimalLimits(BigDecimal.ONE, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new DecimalLimits(BigDecimal.ONE, 2, 0));
        DecimalLimits limits = new DecimalLimits(d("100"), 2, 10);
        assertNull(limits.check(d("100.00"), true));
        assertNotNull(limits.check(d("100.01"), true));
        assertNull(limits.check(d("100.01"), false), "reads have no maximum");
        assertNotNull(limits.check(d("1.001"), false));
        assertNotNull(limits.check(d("1E+50"), false), "absurd read amount");
    }
}
