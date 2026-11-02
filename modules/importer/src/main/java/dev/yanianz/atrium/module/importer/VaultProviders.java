package dev.yanianz.atrium.module.importer;

import java.util.List;
import java.util.Optional;
import org.bukkit.Server;

/**
 * Indirection that keeps {@link VaultBalanceSources} (which links against the Vault API) from being
 * loaded before {@link BalanceSource#vaultInstalled(Server)} returned true. Callers check that first.
 */
final class VaultProviders {
    private VaultProviders() {}

    static List<BalanceSource.Provider> list(Server server, String atriumPluginName) {
        return VaultBalanceSources.providers(server, atriumPluginName);
    }

    static Optional<BalanceSource> find(Server server, String atriumPluginName, String wanted) {
        return VaultBalanceSources.find(server, atriumPluginName, wanted);
    }
}
