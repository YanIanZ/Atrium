package dev.yanianz.atrium.module.scoreboard;

import org.bukkit.Bukkit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Touches {@link TabSidebarBackend} only when the TAB plugin is enabled, so a server without TAB never resolves a
 * TAB class and the optional dependency cannot cause a {@link NoClassDefFoundError}.
 */
final class TabBackendFactory {
    /** Plugin name of TAB by NEZNAMY. */
    static final String PLUGIN = "TAB";

    private TabBackendFactory() {}

    static boolean pluginEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled(PLUGIN);
    }

    /** Whether TAB runs its scoreboard feature (it is off in TAB's default config). Any thread. */
    static boolean scoreboardEnabled(Logger logger) {
        if (!pluginEnabled()) {
            return false;
        }
        try {
            return TabSidebarBackend.featureEnabled();
        } catch (RuntimeException | LinkageError e) {
            logger.warn("TAB is enabled but its API cannot be used; sidebars use another backend", e);
            return false;
        }
    }

    /** @return a ready backend, or {@code null} when TAB's scoreboard API cannot be used */
    static @Nullable SidebarBackend create(Logger logger) {
        if (!pluginEnabled()) {
            return null;
        }
        try {
            return TabSidebarBackend.create(logger);
        } catch (RuntimeException | LinkageError e) {
            logger.warn("TAB is enabled but its scoreboard API cannot be used; sidebars use another backend", e);
            return null;
        }
    }
}
