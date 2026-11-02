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
    enum Kind {
        NONE, BUKKIT, PACKETS, TAB;

        String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    Kind kind();

    /**
     * Global thread, at the start of every update cycle: backends that hand their work to another plugin apply
     * what the player threads recorded since the previous cycle. The others draw directly and do nothing here.
     */
    default void flush() {
    }

    /** Global thread: false when the backend can no longer work (its plugin or feature went away). */
    default boolean usable() {
        return true;
    }

    /**
     * Global thread: removes everything this backend created for every player at once. Backends that draw on
     * player threads do that through {@link #hide(Player)} instead and do nothing here.
     */
    default void stop() {
    }

    /**
     * Global thread, TAB backend only: TAB (re)loaded these players or everyone, so what Atrium set there is gone;
     * the next {@link #flush()} sets it again.
     */
    default void invalidate(dev.yanianz.atrium.module.display.TabResync.Batch batch) {
    }

    /** The plugin behind this backend is being disabled: forget all state without calling into it. */
    default void detach() {
    }

    /**
     * Global thread, TAB backend only (null otherwise), for {@code /sidebar engine}.
     *
     * @param loadedPlayers  players TAB has loaded
     * @param appliedPlayers loaded players whose sidebar slot Atrium currently controls in TAB (its board shown,
     *                       or TAB's own scoreboards hidden by Atrium)
     * @param hiddenDefaults loaded players for whom Atrium hid TAB's own scoreboards
     * @param reapplied      times Atrium found its board gone in TAB and showed it again
     */
    record TabStatus(int loadedPlayers, int appliedPlayers, int hiddenDefaults, long reapplied) {}

    default @org.jspecify.annotations.Nullable TabStatus tabStatus() {
        return null;
    }

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
