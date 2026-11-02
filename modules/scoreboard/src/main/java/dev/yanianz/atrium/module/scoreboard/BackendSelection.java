package dev.yanianz.atrium.module.scoreboard;

import dev.yanianz.atrium.module.scoreboard.SidebarBackend.Kind;

/**
 * Pure backend choice. Kept free of Bukkit so it can be unit-tested and so the decision is explained by
 * one small table instead of being spread through module startup.
 */
final class BackendSelection {
    /** TAB is not installed or not enabled. */
    static final String TAB_PLUGIN_MISSING = "tab-plugin-missing";
    /** TAB is enabled but its scoreboard feature is disabled in TAB's config. */
    static final String TAB_SCOREBOARD_DISABLED = "tab-scoreboard-disabled";
    /** TAB and its scoreboard feature are on, but the API could not be used. */
    static final String TAB_START_FAILED = "tab-start-failed";
    /** The TAB plugin was disabled while the server was running. */
    static final String TAB_PLUGIN_DISABLED = "tab-plugin-disabled";

    private BackendSelection() {}

    /**
     * Backend choice including TAB.
     *
     * @param tabAvailable the TAB plugin is enabled, its scoreboard feature is on and its API is usable
     */
    static Kind select(BackendMode mode, boolean bukkitAvailable, boolean packetsAvailable, boolean tabAvailable) {
        return switch (mode) {
            // TAB first: when TAB runs its scoreboard feature, a second sidebar source would fight it.
            case AUTO -> tabAvailable ? Kind.TAB : select(BackendMode.AUTO, bukkitAvailable, packetsAvailable);
            // An explicit tab falls back to the auto choice without TAB; the caller logs why once.
            case TAB -> tabAvailable ? Kind.TAB : select(BackendMode.AUTO, bukkitAvailable, packetsAvailable);
            case BUKKIT, PACKETS -> select(mode, bukkitAvailable, packetsAvailable);
        };
    }

    /** Why TAB is not usable, or {@code ""} when it is. */
    static String tabProblem(boolean pluginEnabled, boolean scoreboardFeature, boolean apiUsable) {
        if (!pluginEnabled) {
            return TAB_PLUGIN_MISSING;
        }
        if (!scoreboardFeature) {
            return TAB_SCOREBOARD_DISABLED;
        }
        return apiUsable ? "" : TAB_START_FAILED;
    }

    /**
     * Choice among the Bukkit and packet backends only ({@code tab} is treated as {@code auto}).
     *
     * @param mode             configured backend
     * @param bukkitAvailable  the server has a scoreboard API (not Folia)
     * @param packetsAvailable the packetevents plugin is present and usable
     */
    static Kind select(BackendMode mode, boolean bukkitAvailable, boolean packetsAvailable) {
        return switch (mode) {
            // Prefer the native API on Paper: it integrates with other plugins and needs no dependency.
            case AUTO -> bukkitAvailable ? Kind.BUKKIT : packetsAvailable ? Kind.PACKETS : Kind.NONE;
            case BUKKIT -> bukkitAvailable ? Kind.BUKKIT : Kind.NONE;
            case PACKETS -> packetsAvailable ? Kind.PACKETS : Kind.NONE;
            case TAB -> select(BackendMode.AUTO, bukkitAvailable, packetsAvailable);
        };
    }
}
