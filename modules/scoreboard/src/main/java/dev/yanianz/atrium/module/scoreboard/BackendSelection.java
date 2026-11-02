package dev.yanianz.atrium.module.scoreboard;

import dev.yanianz.atrium.module.scoreboard.SidebarBackend.Kind;

/**
 * Pure backend choice. Kept free of Bukkit so it can be unit-tested and so the decision is explained by
 * one small table instead of being spread through module startup.
 */
final class BackendSelection {
    private BackendSelection() {}

    /**
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
        };
    }
}
