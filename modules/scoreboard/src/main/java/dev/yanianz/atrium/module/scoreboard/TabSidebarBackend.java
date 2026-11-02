package dev.yanianz.atrium.module.scoreboard;

import dev.yanianz.atrium.module.display.TabText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.scoreboard.Scoreboard;
import me.neznamy.tab.api.scoreboard.ScoreboardManager;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Sidebar through the TAB plugin's scoreboard API. Atrium still renders every title and line per player; each
 * player gets one TAB scoreboard named {@code atrium-sidebar-<uuid>} that holds their rendered text, shown with
 * {@code showScoreboard} and updated line by line ({@link TabSidebarPlan}) only where the text changed.
 *
 * <p>Threading: {@link #update} and {@link #hide} run on the player's thread and only record what the player
 * should see. {@link #flush} runs on the global thread at the start of every cycle and is the only place that
 * calls into TAB (part of TAB's scoreboard API changes TAB's state on the calling thread, so Atrium's calls are
 * never concurrent and never on a region thread).
 *
 * <p>Ownership: only scoreboards Atrium created are changed or removed. Hiding Atrium's sidebar resets the
 * player's custom scoreboard only while it is Atrium's, so TAB's own scoreboards (or another plugin's) take the
 * slot back. TAB's per-player scoreboard toggle is never touched.
 *
 * <p>Only loaded when TAB is enabled; see {@link TabBackendFactory}.
 */
final class TabSidebarBackend implements SidebarBackend {
    static final String NAME_PREFIX = "atrium-sidebar-";

    /** What a player should see; {@link #HIDDEN} for no Atrium sidebar. */
    private record Wanted(String title, List<String> lines) {}

    private static final Wanted HIDDEN = new Wanted("", List.of());

    /** What TAB holds for a player. Global thread only. */
    private static final class State {
        @Nullable Scoreboard board;
        String title = "";
        List<String> lines = List.of();
        boolean shown;
    }

    private final Logger logger;
    private final Map<UUID, Wanted> wanted = new ConcurrentHashMap<>();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    /** Players who left; their scoreboard is removed on the next flush. */
    private final Queue<UUID> gone = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean tabReloaded = new AtomicBoolean();
    private final Queue<UUID> reloadedPlayers = new ConcurrentLinkedQueue<>();
    private final EventHandler<TabLoadEvent> onTabLoad = event -> tabReloaded.set(true);
    private final EventHandler<PlayerLoadEvent> onPlayerLoad = event -> reloadedPlayers.add(event.getPlayer().getUniqueId());
    private final AtomicBoolean errorReported = new AtomicBoolean();
    private volatile boolean listenersRegistered;
    private volatile boolean detached;

    private TabSidebarBackend(Logger logger) {
        this.logger = logger;
    }

    /** Whether TAB's scoreboard feature is running. Called only when TAB is enabled. */
    static boolean featureEnabled() {
        TabAPI api = TabAPI.getInstance();
        return api != null && api.getScoreboardManager() != null;
    }

    static @Nullable SidebarBackend create(Logger logger) {
        TabAPI api = TabAPI.getInstance();
        if (api == null || api.getScoreboardManager() == null) {
            return null;
        }
        TabSidebarBackend backend = new TabSidebarBackend(logger);
        EventBus bus = api.getEventBus();
        if (bus != null) {
            bus.register(TabLoadEvent.class, backend.onTabLoad);
            bus.register(PlayerLoadEvent.class, backend.onPlayerLoad);
            backend.listenersRegistered = true;
        }
        return backend;
    }

    static String boardName(UUID player) {
        return NAME_PREFIX + player;
    }

    @Override
    public Kind kind() {
        return Kind.TAB;
    }

    @Override
    public boolean canRender(Player player) {
        // TAB decides itself when another plugin's sidebar takes precedence.
        return true;
    }

    @Override
    public int update(Player player, Component title, List<Component> lines) {
        List<String> text = new ArrayList<>(lines.size());
        for (Component line : lines) {
            text.add(TabText.toTab(line));
        }
        Wanted next = new Wanted(TabText.toTab(title), List.copyOf(text));
        Wanted previous = wanted.put(player.getUniqueId(), next);
        if (previous == null || previous == HIDDEN) {
            return next.lines().size() + 1;
        }
        return TabSidebarPlan.plan(previous.title(), previous.lines(), next.title(), next.lines()).size();
    }

    @Override
    public void hide(Player player) {
        // Only players that were given a sidebar need a hide; the render loop calls this for everyone else too.
        wanted.computeIfPresent(player.getUniqueId(), (id, current) -> HIDDEN);
    }

    @Override
    public void forget(Player player) {
        UUID id = player.getUniqueId();
        if (wanted.remove(id) != null || states.containsKey(id)) {
            gone.add(id);
        }
    }

    @Override
    public boolean usable() {
        return !detached && TabBackendFactory.pluginEnabled() && featureEnabled();
    }

    @Override
    public void flush() {
        if (detached) {
            return;
        }
        if (tabReloaded.getAndSet(false)) {
            // TAB dropped every API scoreboard on reload; create them again.
            states.clear();
        }
        for (UUID id; (id = reloadedPlayers.poll()) != null; ) {
            State state = states.get(id);
            if (state != null) {
                state.shown = false;
            }
        }
        TabAPI api = TabAPI.getInstance();
        ScoreboardManager manager = api == null ? null : api.getScoreboardManager();
        if (manager == null) {
            return; // the module notices through usable() and picks another backend
        }
        try {
            for (UUID id; (id = gone.poll()) != null; ) {
                State state = states.remove(id);
                if (state != null && state.board != null) {
                    remove(manager, state.board);
                }
            }
            for (Map.Entry<UUID, Wanted> entry : wanted.entrySet()) {
                TabPlayer player = api.getPlayer(entry.getKey());
                if (player == null || !player.isLoaded()) {
                    continue; // TAB loads players asynchronously; retried on the next flush
                }
                apply(manager, player, entry.getKey(), entry.getValue());
            }
        } catch (IllegalStateException discarded) {
            // TAB was reloaded during this flush and discarded its manager: start over next time.
            tabReloaded.set(true);
        }
    }

    private void apply(ScoreboardManager manager, TabPlayer player, UUID id, Wanted next) {
        State state = states.computeIfAbsent(id, key -> new State());
        try {
            if (next == HIDDEN) {
                if (state.shown) {
                    release(manager, player, state);
                    state.shown = false;
                }
                return;
            }
            if (state.board == null) {
                String name = boardName(id);
                Scoreboard leftover = manager.getRegisteredScoreboards().get(name);
                if (leftover != null) {
                    remove(manager, leftover);
                }
                state.board = manager.createScoreboard(name, next.title(), next.lines());
            } else {
                Scoreboard board = state.board;
                for (TabSidebarPlan.Op op : TabSidebarPlan.plan(state.title, state.lines, next.title(), next.lines())) {
                    switch (op) {
                        case TabSidebarPlan.Title title -> board.setTitle(title.text());
                        case TabSidebarPlan.SetLine set -> board.getLines().get(set.index()).setText(set.text());
                        case TabSidebarPlan.AddLine add -> board.addLine(add.text());
                        case TabSidebarPlan.RemoveLine remove -> board.removeLine(remove.index());
                    }
                }
            }
            state.title = next.title();
            state.lines = next.lines();
            if (!state.shown) {
                manager.showScoreboard(player, state.board);
                state.shown = true;
            }
        } catch (IllegalStateException discarded) {
            throw discarded;
        } catch (RuntimeException error) {
            // Start this player over with a fresh scoreboard next time.
            if (state.board != null) {
                remove(manager, state.board);
            }
            states.remove(id);
            if (errorReported.compareAndSet(false, true)) {
                logger.warn("TAB rejected the sidebar of {}; it is recreated on the next update", player.getName(), error);
            }
        }
    }

    /** Hands the slot back only while Atrium's scoreboard is the player's custom one. */
    private static void release(ScoreboardManager manager, TabPlayer player, State state) {
        if (!manager.hasCustomScoreboard(player)) {
            return;
        }
        Scoreboard active = manager.getActiveScoreboard(player);
        // No active scoreboard: the player hid TAB's scoreboards; the custom one is still Atrium's.
        if (active == null || active == state.board) {
            manager.resetScoreboard(player);
        }
    }

    private void remove(ScoreboardManager manager, Scoreboard board) {
        if (!board.getName().startsWith(NAME_PREFIX)) {
            return; // never remove a scoreboard Atrium did not create
        }
        try {
            manager.removeScoreboard(board);
        } catch (IllegalArgumentException alreadyGone) {
            // Not registered any more (TAB reloaded): nothing to remove.
        }
    }

    @Override
    public void stop() {
        if (detached) {
            return;
        }
        detached = true;
        try {
            TabAPI api = TabBackendFactory.pluginEnabled() ? TabAPI.getInstance() : null;
            ScoreboardManager manager = api == null ? null : api.getScoreboardManager();
            if (manager != null && !tabReloaded.get()) {
                for (Map.Entry<UUID, State> entry : states.entrySet()) {
                    State state = entry.getValue();
                    TabPlayer player = api.getPlayer(entry.getKey());
                    if (state.shown && player != null && player.isLoaded()) {
                        release(manager, player, state);
                    }
                    if (state.board != null) {
                        remove(manager, state.board);
                    }
                }
            }
            EventBus bus = api == null ? null : api.getEventBus();
            if (bus != null && listenersRegistered) {
                bus.unregister(onTabLoad);
                bus.unregister(onPlayerLoad);
            }
        } catch (RuntimeException | LinkageError error) {
            logger.warn("Removing Atrium's sidebars from TAB did not finish cleanly", error);
        } finally {
            wanted.clear();
            states.clear();
            gone.clear();
        }
    }

    @Override
    public void detach() {
        detached = true;
        wanted.clear();
        states.clear();
        gone.clear();
    }

    @Override
    public String toString() {
        return "SidebarBackend[TAB]";
    }
}
