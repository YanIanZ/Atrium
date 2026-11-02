package dev.yanianz.atrium.module.importer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * The only class of the importer that touches the Vault API. It is loaded only after
 * {@link BalanceSource#vaultInstalled(Server)} said the Vault classes are present, so Atrium runs
 * without Vault.
 * Every method must be called on the global region thread.
 */
final class VaultBalanceSources {
    private VaultBalanceSources() {}

    /** Every registered economy provider; Atrium's own provider and the Atrium Vault guard are marked. */
    static List<BalanceSource.Provider> providers(Server server, String atriumPluginName) {
        List<BalanceSource.Provider> out = new ArrayList<>();
        for (RegisteredServiceProvider<Economy> registration : server.getServicesManager().getRegistrations(Economy.class)) {
            Economy economy = registration.getProvider();
            String plugin = registration.getPlugin() == null ? "?" : registration.getPlugin().getName();
            String className = economy.getClass().getName();
            String name = safeName(economy);
            boolean atrium = BalanceSource.isAtriumOwned(plugin, className, atriumPluginName);
            out.add(new BalanceSource.Provider(plugin, name, className, !atrium,
                    atrium ? "Atrium's own provider" : "balances"));
        }
        return out;
    }

    /** The importable provider owned by {@code wanted} (plugin name, else provider name). */
    static Optional<BalanceSource> find(Server server, String atriumPluginName, String wanted) {
        Optional<BalanceSource.Provider> picked = BalanceSource.pick(providers(server, atriumPluginName), wanted);
        if (picked.isEmpty()) {
            return Optional.empty();
        }
        for (RegisteredServiceProvider<Economy> registration : server.getServicesManager().getRegistrations(Economy.class)) {
            String plugin = registration.getPlugin() == null ? "?" : registration.getPlugin().getName();
            if (plugin.equals(picked.get().pluginName())
                    && registration.getProvider().getClass().getName().equals(picked.get().className())) {
                return Optional.of(wrap(server, plugin, registration.getProvider()));
            }
        }
        return Optional.empty();
    }

    /** Adapts one Vault provider. */
    static BalanceSource wrap(Server server, String plugin, Economy economy) {
        return new BalanceSource() {
            @Override
            public String pluginName() {
                return plugin;
            }

            @Override
            public String providerName() {
                return safeName(economy);
            }

            @Override
            public int fractionalDigits() {
                try {
                    return economy.fractionalDigits();
                } catch (RuntimeException e) {
                    return -1;
                }
            }

            @Override
            public Optional<Double> balance(UUID player, String name) {
                OfflinePlayer offline = server == null ? null : server.getOfflinePlayer(player);
                return VaultBalanceSources.balance(economy, offline);
            }
        };
    }

    /** Reads one balance; a provider that has no account (or throws) yields empty. */
    static Optional<Double> balance(Economy economy, OfflinePlayer player) {
        if (player == null || !economy.hasAccount(player)) {
            return Optional.empty();
        }
        return Optional.of(economy.getBalance(player));
    }

    private static String safeName(Economy economy) {
        try {
            String name = economy.getName();
            return name == null || name.isBlank() ? economy.getClass().getSimpleName() : name;
        } catch (RuntimeException e) {
            return economy.getClass().getSimpleName();
        }
    }
}
