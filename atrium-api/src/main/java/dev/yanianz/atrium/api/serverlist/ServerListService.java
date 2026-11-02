package dev.yanianz.atrium.api.serverlist;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * What the server list (multiplayer screen) shows. Published by the {@code performance} module, which owns the
 * MOTD override and the number manipulation; other modules (for example {@code customtext} with
 * {@code /setmotd}) change it through this interface instead of editing another module's files.
 *
 * <p>MOTD lines are MiniMessage templates. {@code <online>} and {@code <max>} are replaced with the numbers the
 * client will see. The override is stored by Atrium under {@code plugins/Atrium/data/performance/motd.yml}, so
 * it survives a restart without touching {@code server.properties}. It never rewrites that file.
 *
 * <p>All methods are safe to call from any thread; the setters return a future that completes after the new
 * value was written to disk. A failed write completes exceptionally and keeps the previous value.
 */
public interface ServerListService {
    /** Whether the MOTD override is on. */
    boolean motdEnabled();

    /** The configured MOTD lines (MiniMessage), at most two; empty when no override was ever set. */
    List<String> motdLines();

    /**
     * Replaces the MOTD lines and enables the override. An empty list turns the override off. Values are
     * trimmed, empty lines dropped, brackets drained to two lines and each line is truncated to a safe length.
     */
    CompletableFuture<Void> setMotd(List<String> lines);

    /** Turns the MOTD override on or off without changing the stored lines. */
    CompletableFuture<Void> setMotdEnabled(boolean enabled);

    /** The number the server list currently reports. */
    int reportedOnline();

    /** The cap the server list currently reports. */
    int reportedMax();
}
