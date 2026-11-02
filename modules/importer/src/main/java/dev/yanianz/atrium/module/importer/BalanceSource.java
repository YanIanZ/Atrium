package dev.yanianz.atrium.module.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Balances of another economy plugin, independent of how they are reached (Vault today). Implementations
 * are <em>not</em> thread-safe: many economy plugins expect to be called on the server thread, so the
 * import job calls them on the global region thread, a bounded batch at a time.
 */
interface BalanceSource {
    /** Owning plugin name, e.g. {@code XConomy}. */
    String pluginName();

    /** Provider name as it reports itself. */
    String providerName();

    /** Decimal digits the provider keeps, or -1 when it does not say. */
    int fractionalDigits();

    /**
     * The balance of {@code player}, or empty when the provider has no account for them. {@code name} is
     * the last known name (some providers key accounts by name).
     */
    Optional<Double> balance(UUID player, String name);

    /** One registered economy provider, for {@code /atrium-import sources}. */
    record Provider(String pluginName, String providerName, String className, boolean importable, String why) {}

    /**
     * Whether the Vault API is installed (the real Vault plugin or Atrium Vault). Lives here, not in
     * {@link VaultBalanceSources}, because loading that class without Vault would fail.
     */
    static boolean vaultInstalled(org.bukkit.Server server) {
        if (server.getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        try {
            Class.forName("net.milkbowl.vault.economy.Economy", false, BalanceSource.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /** Converts a provider's double to an exact decimal (see {@link #toDecimal(double, int)}). */
    static Optional<BigDecimal> exact(double value, int fractionalDigits) {
        return toDecimal(value, fractionalDigits);
    }

    /**
     * {@code double} to an exact decimal: the shortest decimal that round-trips the double
     * ({@link BigDecimal#valueOf(double)}, so {@code 0.1} stays {@code 0.1} instead of
     * {@code 0.1000000000000000055...}), rounded half-even to the provider's {@code fractionalDigits} when it
     * reports one (0-8). NaN, infinities and negative values are rejected.
     */
    static Optional<BigDecimal> toDecimal(double value, int fractionalDigits) {
        if (!Double.isFinite(value) || value < 0) {
            return Optional.empty();
        }
        BigDecimal decimal = BigDecimal.valueOf(value);
        if (fractionalDigits >= 0 && fractionalDigits <= 8) {
            decimal = decimal.setScale(fractionalDigits, RoundingMode.HALF_EVEN);
        }
        decimal = decimal.stripTrailingZeros();
        if (decimal.scale() < 0) {
            decimal = decimal.setScale(0);
        }
        return Optional.of(decimal);
    }

    /** Ledger source for a provider: {@code vault:<plugin>}, cut to the ledger column size. */
    static String ledgerSource(String pluginName) {
        String source = "vault:" + pluginName;
        return source.length() <= 32 ? source : source.substring(0, 32);
    }

    /** Never an Atrium provider: Atrium's own economy or the Atrium Vault guard that forwards to it. */
    static boolean isAtriumOwned(String pluginName, String className, String atriumPluginName) {
        return pluginName.equalsIgnoreCase(atriumPluginName)
                || (className != null && className.startsWith("dev.yanianz.atrium."));
    }

    /** Picks the provider whose owning plugin (or, as a fallback, provider name) matches {@code wanted}. */
    static Optional<Provider> pick(List<Provider> providers, String wanted) {
        for (Provider provider : providers) {
            if (provider.importable() && provider.pluginName().equalsIgnoreCase(wanted)) {
                return Optional.of(provider);
            }
        }
        for (Provider provider : providers) {
            if (provider.importable() && provider.providerName().equalsIgnoreCase(wanted)) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }
}
