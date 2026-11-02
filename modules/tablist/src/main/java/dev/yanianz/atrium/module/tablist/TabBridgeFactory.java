package dev.yanianz.atrium.module.tablist;

import dev.yanianz.atrium.api.service.ServiceRegistry;
import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Loads {@link TabApiBridge} only when the TAB plugin is enabled. Keeping the check in this small class means a
 * server without TAB never resolves a TAB class, so the optional dependency cannot cause a
 * {@link NoClassDefFoundError} in the tablist module.
 */
final class TabBridgeFactory {
    private TabBridgeFactory() {}

    static boolean pluginEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled(TablistEngineSelection.TAB_PLUGIN);
    }

    /** @return a ready bridge, or {@code null} when TAB is missing or its API cannot be used */
    static @Nullable TabBridge create(Logger logger, ServiceRegistry services) {
        if (!pluginEnabled()) {
            return null;
        }
        try {
            return TabApiBridge.create(logger, services);
        } catch (RuntimeException | LinkageError e) {
            // A broken or incompatible TAB must not take the whole module down.
            logger.warn("TAB is enabled but its API cannot be used; the tab list uses the native engine", e);
            return null;
        }
    }
}
