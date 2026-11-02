package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;

/** The generic Vault economy import, against fake Vault providers (no server needed). */
class EconomyImportTest {
    private static final UUID RICH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID POOR = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NONE = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID BROKEN = UUID.fromString("00000000-0000-0000-0000-000000000004");

    /** A Vault provider that knows a fixed set of balances and throws for {@link #BROKEN}. */
    private static Economy fakeEconomy(String name, int digits, Map<UUID, Double> balances) {
        return proxy(Economy.class, (method, args) -> switch (method) {
            case "getName" -> name;
            case "isEnabled" -> true;
            case "fractionalDigits" -> digits;
            case "hasAccount" -> {
                UUID id = ((OfflinePlayer) args[0]).getUniqueId();
                yield id.equals(BROKEN) || balances.containsKey(id);
            }
            case "getBalance" -> {
                UUID id = ((OfflinePlayer) args[0]).getUniqueId();
                if (id.equals(BROKEN)) {
                    throw new IllegalStateException("database offline");
                }
                yield balances.get(id);
            }
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static OfflinePlayer offline(UUID id) {
        return proxy(OfflinePlayer.class, (method, args) -> switch (method) {
            case "getUniqueId" -> id;
            case "getName" -> null;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static Plugin plugin(String name) {
        return proxy(Plugin.class, (method, args) -> switch (method) {
            case "getName" -> name;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static Server server(List<RegisteredServiceProvider<Economy>> registrations) {
        ServicesManager services = proxy(ServicesManager.class, (method, args) -> switch (method) {
            case "getRegistrations" -> new ArrayList<>(registrations);
            default -> throw new UnsupportedOperationException(method);
        });
        return proxy(Server.class, (method, args) -> switch (method) {
            case "getServicesManager" -> services;
            case "getOfflinePlayer" -> offline((UUID) args[0]);
            default -> throw new UnsupportedOperationException(method);
        });
    }

    interface Handler {
        Object handle(String method, Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Handler handler) {
        return (T) Proxy.newProxyInstance(EconomyImportTest.class.getClassLoader(), new Class<?>[] {type},
                (self, method, args) -> switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> type.getSimpleName() + " fake";
                    default -> handler.handle(method.getName(), args);
                });
    }

    private static RegisteredServiceProvider<Economy> registration(Economy economy, String plugin) {
        return new RegisteredServiceProvider<>(Economy.class, economy, ServicePriority.Normal, plugin(plugin));
    }

    @Test
    void readsBalancesInABatchAndKeepsGoingAfterAProviderError() {
        Economy economy = fakeEconomy("XConomy", 2, Map.of(RICH, 1234.567, POOR, 0.1));
        BalanceSource source = VaultBalanceSources.wrap(server(List.of()), "XConomy", economy);
        assertEquals("XConomy", source.providerName());
        assertEquals(2, source.fractionalDigits());

        List<Map.Entry<UUID, String>> batch = List.of(Map.entry(RICH, "Rich"), Map.entry(BROKEN, "Broken"),
                Map.entry(POOR, "Poor"), Map.entry(NONE, "None"));
        Map<UUID, EconomyImportJob.Read> reads = EconomyImportJob.readBatch(source, batch);

        assertEquals(4, reads.size());
        assertEquals(1234.567, reads.get(RICH).balance());
        assertNull(reads.get(RICH).error());
        assertNotNull(reads.get(BROKEN).error(), "a throwing provider becomes a failed row, not a failed import");
        assertTrue(reads.get(BROKEN).error().contains("database offline"));
        assertEquals(0.1, reads.get(POOR).balance());
        assertNull(reads.get(NONE).balance(), "no account");
        assertNull(reads.get(NONE).error());

        // The provider's 2 fractional digits decide the stored precision (half-even).
        assertEquals(new BigDecimal("1234.57"), BalanceSource.toDecimal(reads.get(RICH).balance(), source.fractionalDigits()).orElseThrow());
        assertEquals(new BigDecimal("0.1"), BalanceSource.toDecimal(reads.get(POOR).balance(), source.fractionalDigits()).orElseThrow());
    }

    @Test
    void decimalsAreExactAndRejectBrokenValues() {
        assertEquals(new BigDecimal("0.1"), BalanceSource.toDecimal(0.1, -1).orElseThrow());
        assertEquals(new BigDecimal("100"), BalanceSource.toDecimal(100.0, 2).orElseThrow());
        assertEquals(new BigDecimal("2"), BalanceSource.toDecimal(2.5, 0).orElseThrow());
        assertEquals(new BigDecimal("4"), BalanceSource.toDecimal(3.5, 0).orElseThrow());
        assertEquals(new BigDecimal("0.12345679"), BalanceSource.toDecimal(0.123456789, 12).map(d -> d).orElseThrow()
                .setScale(8, java.math.RoundingMode.HALF_EVEN));
        assertTrue(BalanceSource.toDecimal(Double.NaN, 2).isEmpty());
        assertTrue(BalanceSource.toDecimal(Double.POSITIVE_INFINITY, 2).isEmpty());
        assertTrue(BalanceSource.toDecimal(-5, 2).isEmpty());
    }

    @Test
    void listsProvidersAndNeverPicksAtriumItself() {
        Economy royale = fakeEconomy("RoyaleEconomy", 2, Map.of(RICH, 5.0));
        Economy atrium = fakeEconomy("Atrium", 2, Map.of());
        Server server = server(List.of(registration(atrium, "Atrium"), registration(royale, "RoyaleEconomy")));

        List<BalanceSource.Provider> providers = VaultBalanceSources.providers(server, "Atrium");
        assertEquals(2, providers.size());
        assertFalse(providers.get(0).importable(), "Atrium's own provider is listed but not importable");
        assertTrue(providers.get(1).importable());

        assertTrue(VaultBalanceSources.find(server, "Atrium", "Atrium").isEmpty());
        Optional<BalanceSource> found = VaultBalanceSources.find(server, "Atrium", "royaleeconomy");
        assertTrue(found.isPresent(), "plugin names match case-insensitively");
        assertEquals("RoyaleEconomy", found.get().pluginName());
        assertEquals(Optional.of(5.0), found.get().balance(RICH, "Rich"));
        assertEquals(Optional.empty(), found.get().balance(NONE, "None"));
        assertTrue(VaultBalanceSources.find(server, "Atrium", "XConomy").isEmpty());
    }

    @Test
    void atriumGuardAndLedgerNames() {
        assertTrue(BalanceSource.isAtriumOwned("Vault", "dev.yanianz.atrium.vault.Guard", "Atrium"));
        assertTrue(BalanceSource.isAtriumOwned("atrium", "x.Y", "Atrium"));
        assertFalse(BalanceSource.isAtriumOwned("Essentials", "com.earth2me.essentials.Economy", "Atrium"));
        assertEquals("vault:XConomy", BalanceSource.ledgerSource("XConomy"));
        assertEquals(32, BalanceSource.ledgerSource("A".repeat(40)).length());
        assertEquals("tne", EconomyImportJob.safe("TNE"));

        List<BalanceSource.Provider> providers = List.of(
                new BalanceSource.Provider("Essentials", "EssentialsX Economy", "a.B", true, "balances"),
                new BalanceSource.Provider("Atrium", "Atrium", "dev.yanianz.atrium.X", false, "own"));
        assertEquals("Essentials", BalanceSource.pick(providers, "essentialsx economy").orElseThrow().pluginName());
        assertTrue(BalanceSource.pick(providers, "Atrium").isEmpty());
    }
}
