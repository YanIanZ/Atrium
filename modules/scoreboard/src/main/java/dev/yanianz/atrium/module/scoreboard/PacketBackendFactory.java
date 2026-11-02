package dev.yanianz.atrium.module.scoreboard;

import org.bukkit.Bukkit;
import org.slf4j.Logger;

/**
 * Loads {@link PacketSidebarBackend} only when the packetevents plugin is enabled. Keeping the lookup in
 * this tiny class means a server without PacketEvents never resolves any PacketEvents class, so the
 * optional dependency cannot cause a {@link NoClassDefFoundError} in the scoreboard module.
 */
final class PacketBackendFactory {
    static final String PLUGIN = "packetevents";

    private PacketBackendFactory() {}

    /** @return a ready backend, or {@code null} when packets cannot be used on this server */
    static SidebarBackend create(Logger logger, boolean folia) {
        // Checked first: earlier in the same class would be fine too, but never touch PacketEvents before this.
        if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
            return null;
        }
        try {
            return PacketSidebarBackend.create(logger, folia);
        } catch (RuntimeException | LinkageError e) {
            // A broken or incompatible PacketEvents must not take the whole module down.
            logger.warn("PacketEvents is enabled but its API cannot be used; sidebars will use another backend", e);
            return null;
        }
    }
}
