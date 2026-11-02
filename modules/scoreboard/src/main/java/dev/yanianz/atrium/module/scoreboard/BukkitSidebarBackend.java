package dev.yanianz.atrium.module.scoreboard;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.slf4j.Logger;

/**
 * Sidebar through the Bukkit scoreboard API (Paper). Each player gets a private scoreboard on their own
 * thread; the server's main scoreboard is never touched.
 */
final class BukkitSidebarBackend implements SidebarBackend {
    private final Logger logger;
    private final Map<UUID, Sidebar> sidebars = new ConcurrentHashMap<>();
    /** Set when this server advertises the API but cannot create boards; logged once. */
    private volatile boolean unavailable;

    BukkitSidebarBackend(Logger logger) {
        this.logger = logger;
    }

    @Override
    public Kind kind() {
        return Kind.BUKKIT;
    }

    @Override
    public boolean canRender(Player player) {
        Sidebar ours = sidebars.get(player.getUniqueId());
        Scoreboard current = player.getScoreboard();
        return current == main() || (ours != null && current == ours.board());
    }

    @Override
    public int update(Player player, Component title, List<Component> lines) {
        if (unavailable) {
            return 0;
        }
        Sidebar sidebar = sidebars.get(player.getUniqueId());
        if (sidebar == null) {
            Scoreboard board;
            try {
                board = Bukkit.getScoreboardManager().getNewScoreboard();
            } catch (UnsupportedOperationException e) {
                unavailable = true;
                logger.warn("This server cannot create per-player scoreboards; sidebars are disabled", e);
                return 0;
            }
            sidebar = new Sidebar(board, title);
            sidebars.put(player.getUniqueId(), sidebar);
        }
        int changed = sidebar.update(title, lines);
        if (!sidebar.isShownTo(player)) {
            player.setScoreboard(sidebar.board());
        }
        return changed;
    }

    @Override
    public void hide(Player player) {
        Sidebar sidebar = sidebars.remove(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        Scoreboard main = main();
        if (sidebar != null) {
            if (sidebar.isShownTo(player)) {
                player.setScoreboard(main);
            }
            return;
        }
        // State can already be gone while a hide scheduled at shutdown is still running. Only take back a
        // board that carries our own objective, never another plugin's.
        Scoreboard current = player.getScoreboard();
        if (current != main && current.getObjective(Sidebar.OBJECTIVE) != null) {
            player.setScoreboard(main);
        }
    }

    @Override
    public void forget(Player player) {
        sidebars.remove(player.getUniqueId());
    }

    private static Scoreboard main() {
        return Bukkit.getScoreboardManager().getMainScoreboard();
    }
}
