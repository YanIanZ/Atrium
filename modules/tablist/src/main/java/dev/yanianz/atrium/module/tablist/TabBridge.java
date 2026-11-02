package dev.yanianz.atrium.module.tablist;

import dev.yanianz.atrium.module.display.TabResync;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The TAB engine as seen by {@link TablistModule}. This interface references no TAB class; the implementation
 * ({@link TabApiBridge}) is only loaded by {@link TabBridgeFactory} after it checked that TAB is enabled, so a
 * server without TAB never resolves TAB's API.
 *
 * <p>Threading: {@link #offer}, {@link #forget}, {@link #resendAll} and {@link #capabilities} may be called from
 * any thread and only record or read state. Every method that calls into TAB ({@link #flush}, {@link #invalidate},
 * {@link #status}, {@link #debug}, {@link #stop}) runs on the global thread, so Atrium's calls into TAB never run
 * concurrently and never on a region thread.
 */
interface TabBridge {
    /**
     * What Atrium wants TAB to show for one player, already converted to TAB's text format.
     *
     * @param listName list name, or {@code null} when Atrium does not manage list names (set-list-name: false)
     */
    record Frame(String header, String footer, @Nullable String listName) {}

    /**
     * Global-thread view for {@code /tablist engine}.
     *
     * @param loadedPlayers  players TAB has loaded
     * @param appliedPlayers loaded players that currently carry Atrium's overrides
     * @param reapplied      overrides Atrium found missing in TAB and handed over again since the engine started
     * @param layout         TAB's layout feature is on (it replaces the whole player list with its own slots)
     */
    record Status(int loadedPlayers, int appliedPlayers, long reapplied, boolean layout) {}

    /**
     * One part of a player's tab entry for {@code /tablist debug}: what Atrium set, what TAB currently holds
     * (read back through its API) and what TAB's own configuration says. {@code null} means "not set".
     */
    record DebugRow(String part, @Nullable String atrium, @Nullable String tabNow, @Nullable String tabConfig,
            boolean readable) {
        /** Atrium manages this part and TAB holds something else: TAB's content is shown (or stacked). */
        boolean mismatch() {
            return readable && atrium != null && !atrium.equals(tabNow);
        }
    }

    /** Any thread: records the latest frame of a player and wakes the resync when it changed. */
    void offer(UUID player, Frame frame);

    /**
     * Global thread: pushes every frame that differs from what TAB holds.
     *
     * @param teamNames forced team names for sorting, or {@code null} when Atrium does not sort the list
     */
    void flush(@Nullable Map<UUID, String> teamNames);

    /** Global thread: TAB dropped the overrides of these players (or everyone); the next flush hands them over. */
    void invalidate(TabResync.Batch batch);

    /** Any thread: a player left; TAB drops its API overrides by itself. */
    void forget(UUID player);

    /** Any thread: the next {@link #flush} hands every part over again, also unchanged ones. */
    void resendAll();

    /** Any thread: which TAB features existed at the last flush. */
    TablistCapabilities capabilities();

    /** Global thread: whether the TAB API can still be used. */
    boolean usable();

    /** Global thread. */
    Status status();

    /** Global thread: what Atrium set and what TAB holds for one player; empty when TAB has not loaded them. */
    List<DebugRow> debug(UUID player);

    /** Global thread: removes every override Atrium set in TAB and unregisters Atrium's TAB listeners. */
    void stop();

    /** TAB is being disabled: forget everything without calling into TAB again. */
    void detach();
}
