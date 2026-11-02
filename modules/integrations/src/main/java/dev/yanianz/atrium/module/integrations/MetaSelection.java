package dev.yanianz.atrium.module.integrations;

/** Pure provider selection, tested independently of Bukkit. */
final class MetaSelection {
    private MetaSelection() {}

    static IntegrationsConfig.MetaBackend choose(IntegrationsConfig.MetaBackend configured,
            boolean luckPerms, boolean vaultChat) {
        if (configured == IntegrationsConfig.MetaBackend.LUCKPERMS) {
            return luckPerms ? IntegrationsConfig.MetaBackend.LUCKPERMS : IntegrationsConfig.MetaBackend.NONE;
        }
        if (configured == IntegrationsConfig.MetaBackend.VAULT) {
            return vaultChat ? IntegrationsConfig.MetaBackend.VAULT : IntegrationsConfig.MetaBackend.NONE;
        }
        if (configured == IntegrationsConfig.MetaBackend.NONE) {
            return IntegrationsConfig.MetaBackend.NONE;
        }
        if (luckPerms) {
            return IntegrationsConfig.MetaBackend.LUCKPERMS;
        }
        return vaultChat ? IntegrationsConfig.MetaBackend.VAULT : IntegrationsConfig.MetaBackend.NONE;
    }
}
