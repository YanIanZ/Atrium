package dev.yanianz.atrium.module.tablist;

import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The TAB engine as seen by {@link TablistModule}. This interface references no TAB class; the implementation
 * ({@link TabApiBridge}) is only loaded by {@link TabBridgeFactory} after it checked that TAB is enabled, so a
 * server without TAB never resolves TAB's API.
 *
 * <p>Threading: {@link #offer} and {@link #forget} are called from player threads and only record state.
 * {@link #flush}, {@link #stop} and {@link #capabilities} call into TAB and run on the global thread, so Atrium's
 * calls into TAB never run concurrently and never on a region thread.
 */
interface TabBridge {
    /**
     * What Atrium wants TAB to show for one player, already converted to TAB's text format.
     *
     * @param listName list name, or {@code null} when Atrium does not manage list names (set-list-name: false)
     */
    record Frame(String header, String footer, @Nullable String listName) {}

    /** Any thread: records the latest frame of a player; applied by the next {@link #flush}. */
    void offer(UUID player, Frame frame);

    /**
     * Global thread: pushes every frame that differs from what TAB was last given.
     *
     * @param teamNames forced team names for sorting, or {@code null} when Atrium does not sort the list
     */
    void flush(@Nullable Map<UUID, String> teamNames);

    /** Any thread: a player left; TAB drops its API overrides by itself. */
    void forget(UUID player);

    /** Any thread: the next {@link #flush} hands every part over again, also unchanged ones. */
    void resendAll();

    /** Any thread (only reads which TAB features exist): which parts TAB currently lets Atrium control. */
    TablistCapabilities capabilities();

    /** Global thread: whether the TAB API can still be used. */
    boolean usable();

    /** Global thread: removes every override Atrium set in TAB and unregisters Atrium's TAB listeners. */
    void stop();

    /** TAB is being disabled: forget everything without calling into TAB again. */
    void detach();
}
