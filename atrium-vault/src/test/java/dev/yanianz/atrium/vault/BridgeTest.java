package dev.yanianz.atrium.vault;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault2.economy.EconomyResponse.ResponseType;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
class BridgeTest {
    private static final String PLUGIN = "Shop";
    private final Map<UUID, OfflinePlayer> players = new HashMap<>();
    private final Map<String, UUID> names = new HashMap<>();
    private OfflinePlayer alice;
    private MapEconomy v1;
    private MapEconomyV2 v2;
    private V1AsV2Economy v1AsV2;
    private V2AsV1Economy v2AsV1;

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @BeforeEach
    void setUp() {
        alice = Players.offline("alice");
        players.put(alice.getUniqueId(), alice);
        names.put("alice", alice.getUniqueId());
        v1 = new MapEconomy("Legacy");
        v1.balances.put("alice", 100.0);
        v2 = new MapEconomyV2("Modern");
        v2.balances.put(alice.getUniqueId(), d("100.00"));
        v1AsV2 = new V1AsV2Economy(() -> v1, players::get);
        v2AsV1 = new V2AsV1Economy(() -> v2, names::get, () -> PLUGIN);
    }

    // ------------------------------------------------------------------ v1 provider seen through v2

    @Test
    void v1BalancesBecomeExactDecimalsAtTheProvidersPrecision() {
        assertEquals(d("100.00"), v1AsV2.balance(PLUGIN, alice.getUniqueId()));
        v1.balances.put("alice", 10.100000000000001); // double noise of 10.1
        assertEquals(d("10.10"), v1AsV2.balance(PLUGIN, alice.getUniqueId()));
        v1.balances.put("alice", 0.125);
        assertEquals(d("0.12"), v1AsV2.balance(PLUGIN, alice.getUniqueId()), "HALF_EVEN");
        v1.digits = -1;
        assertEquals(d("0.125"), v1AsV2.balance(PLUGIN, alice.getUniqueId()), "no rounding without fractional digits");
    }

    @Test
    void v2AmountsReachV1OnlyWhenExact() {
        var ok = v1AsV2.deposit(PLUGIN, alice.getUniqueId(), d("0.10"));
        assertEquals(ResponseType.SUCCESS, ok.type);
        assertEquals(d("0.10"), ok.amount);
        assertEquals(d("100.10"), ok.balance);
        assertEquals("", ok.errorMessage, "v2 messages are never null");

        var tooPrecise = v1AsV2.withdraw(PLUGIN, alice.getUniqueId(), d("1.005"));
        assertEquals(ResponseType.FAILURE, tooPrecise.type);
        assertTrue(tooPrecise.errorMessage.contains("decimal places"));
        assertEquals(ResponseType.FAILURE, v1AsV2.withdraw(PLUGIN, alice.getUniqueId(), d("-1")).type);
        assertEquals(ResponseType.FAILURE, v1AsV2.withdraw(PLUGIN, alice.getUniqueId(), null).type);
        assertEquals(ResponseType.FAILURE, v1AsV2.deposit(PLUGIN, null, d("1")).type);
        v1.digits = -1;
        assertEquals(ResponseType.FAILURE, v1AsV2.deposit(PLUGIN, alice.getUniqueId(), d("9007199254740993")).type,
                "2^53+1 is not a double");
        assertEquals(1, v1.writes.get(), "only the exact deposit reached the provider");
    }

    @Test
    void v1FailuresAndCurrenciesAreMapped() {
        var refused = v1AsV2.withdraw(PLUGIN, alice.getUniqueId(), d("500"));
        assertEquals(ResponseType.FAILURE, refused.type);
        assertEquals("Insufficient funds", refused.errorMessage);
        assertEquals("dollars", v1AsV2.getDefaultCurrency(PLUGIN));
        assertTrue(v1AsV2.hasCurrency("Dollar"));
        assertEquals(ResponseType.FAILURE, v1AsV2.deposit(PLUGIN, alice.getUniqueId(), "world", "gems", d("1")).type);
        assertTrue(v1AsV2.deposit(PLUGIN, alice.getUniqueId(), "world", "dollars", d("1")).transactionSuccess());
        assertTrue(v1AsV2.has(PLUGIN, alice.getUniqueId(), d("101")));
        assertFalse(v1AsV2.has(PLUGIN, alice.getUniqueId(), d("-1")));
        assertFalse(v1AsV2.has(PLUGIN, alice.getUniqueId(), null));
        assertFalse(v1AsV2.createAccount(UUID.randomUUID(), "bank", false), "only player accounts");
    }

    // ------------------------------------------------------------------ v2 provider seen through v1

    @Test
    void v1DoublesAreRoundedToTheV2ProvidersDigits() {
        EconomyResponse response = v2AsV1.depositPlayer(alice, 0.1 + 0.2);
        assertTrue(response.transactionSuccess());
        assertEquals(d("0.30"), v2.amounts.getLast(), "0.30000000000000004 is meant as 0.30");
        assertEquals(100.30, response.balance);
        assertEquals(100.30, v2AsV1.getBalance(alice));
        v2AsV1.withdrawPlayer(alice, 0.125);
        assertEquals(d("0.12"), v2.amounts.getLast(), "HALF_EVEN");
        assertEquals(PLUGIN, v2.plugins.getLast(), "the calling plugin is the v2 plugin name");
    }

    @Test
    void invalidV1AmountsNeverReachTheV2Provider() {
        for (double bad : new double[] {Double.NaN, -0.01, Double.POSITIVE_INFINITY}) {
            assertEquals(net.milkbowl.vault.economy.EconomyResponse.ResponseType.FAILURE,
                    v2AsV1.depositPlayer(alice, bad).type, "deposit " + bad);
            assertFalse(v2AsV1.has(alice, bad));
        }
        assertEquals(net.milkbowl.vault.economy.EconomyResponse.ResponseType.FAILURE,
                v2AsV1.withdrawPlayer((OfflinePlayer) null, 1).type);
        assertEquals(0, v2.writes.get());
    }

    @Test
    void legacyNamesAndWorldsAreResolved() {
        assertEquals(100.0, v2AsV1.getBalance("alice"));
        assertTrue(v2AsV1.depositPlayer("alice", "world_nether", 5).transactionSuccess());
        assertEquals("world_nether", v2.worlds.getLast());
        assertEquals(net.milkbowl.vault.economy.EconomyResponse.ResponseType.FAILURE,
                v2AsV1.depositPlayer("nobody", 5).type, "unknown names have no account");
        assertFalse(v2AsV1.hasAccount("nobody"));
        assertEquals(net.milkbowl.vault.economy.EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                v2AsV1.bankBalance("x").type);
        V2AsV1Economy anonymous = new V2AsV1Economy(() -> v2, names::get, CallerResolver.NONE);
        anonymous.getBalance(alice);
        assertEquals("Vault", v2.plugins.getLast(), "fallback plugin name");
    }

    // ------------------------------------------------------------------ wiring rules

    @Test
    void bridgePlanNeverDoubleRegisters() {
        assertEquals(BridgeManager.Plan.V1_AS_V2, BridgeManager.Plan.of(true, false));
        assertEquals(BridgeManager.Plan.V2_AS_V1, BridgeManager.Plan.of(false, true));
        assertEquals(BridgeManager.Plan.NONE, BridgeManager.Plan.of(true, true), "both native: no bridge");
        assertEquals(BridgeManager.Plan.NONE, BridgeManager.Plan.of(false, false));
    }

    @Test
    void guardsAndBridgesAreNeverNative() {
        RateLimitedLog log = new RateLimitedLog(Logger.getAnonymousLogger(), 0);
        GuardedEconomy guard = new GuardedEconomy(List::of, GuardSettings.defaults(), log, null, null);
        GuardedEconomyV2 guardV2 = new GuardedEconomyV2(List::of, DecimalLimits.of(GuardSettings.defaults()), log, null, null);
        assertFalse(BridgeManager.isNative(guard));
        assertFalse(BridgeManager.isNative(guardV2));
        assertFalse(BridgeManager.isNative(v1AsV2));
        assertFalse(BridgeManager.isNative(v2AsV1));
        assertTrue(BridgeManager.isNative(v1));
        assertTrue(BridgeManager.isNative(v2));

        // A native-provider cache over [guard, bridge, real] picks the real one: bridges cannot feed each other.
        ProviderCache<Object> cache = new ProviderCache<>(() -> List.of(guard, v2AsV1, v1AsV2, v1),
                BridgeManager::isNative, log, "test");
        assertSame(v1, cache.get());
    }

    @Test
    void v1GuardOverTheV2BridgeValidatesAndReachesTheV2Provider() {
        RateLimitedLog log = new RateLimitedLog(Logger.getAnonymousLogger(), 0);
        GuardedEconomy guard = new GuardedEconomy(() -> List.of(v2AsV1), GuardSettings.defaults(), log, null, null);
        assertTrue(guard.depositPlayer(alice, 2.5).transactionSuccess());
        assertEquals(d("102.50"), v2.balances.get(alice.getUniqueId()));
        assertFalse(guard.depositPlayer(alice, Double.NaN).transactionSuccess());

        GuardedEconomyV2 guardV2 = new GuardedEconomyV2(() -> List.of(v1AsV2), DecimalLimits.of(GuardSettings.defaults()),
                log, null, null);
        assertTrue(guardV2.deposit(PLUGIN, alice.getUniqueId(), d("2.50")).transactionSuccess());
        assertEquals(102.5, v1.balances.get("alice"));
        assertSame(v1AsV2, guardV2.delegate());
    }
}
