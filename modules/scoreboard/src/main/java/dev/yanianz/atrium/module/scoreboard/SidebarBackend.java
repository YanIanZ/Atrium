package dev.yanianz.atrium.module.scoreboard;

import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/**
 * Renders one player's private sidebar. Apart from {@link #forget(Player)} every method runs on the
 * player's own thread, so implementations keep per-player state without locking.
 *
 * <p>Implementations only send what changed; an update that does not change anything sends nothing.
 * {@link #hide(Player)} is idempotent and cheap when nothing is shown, which lets the render loop call
 * it unconditionally.
 */
interface SidebarBackend {
    enum Kind { NONE, BUKKIT, PACKETS }

    Kind kind();

    /**
     * Player thread. Whether this player may show our sidebar: another plugin's private scoreboard is
     * never taken over.
     */
    boolean canRender(Player player);

    /**
     * Player thread. Shows the sidebar or applies only the differences from the previous call.
     *
     * @return the number of changed lines, the title counting as one
     */
    int update(Player player, Component title, List<Component> lines);

    /** Player thread. Removes the sidebar if this backend showed one. */
    void hide(Player player);

    /** Drops cached state when the player leaves; sends no packets. */
    void forget(Player player);

    /** Backend for servers that can render no sidebar at all. */
    SidebarBackend NONE = new SidebarBackend() {
        @Override
        public Kind kind() {
            return Kind.NONE;
        }

        @Override
        public boolean canRender(Player player) {
            return false;
        }

        @Override
        public int update(Player player, Component title, List<Component> lines) {
            return 0;
        }

        @Override
        public void hide(Player player) {
        }

        @Override
        public void forget(Player player) {
        }

        @Override
        public String toString() {
            return "SidebarBackend[NONE]";
        }
    };
}
