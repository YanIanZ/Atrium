package dev.yanianz.atrium.module.scoreboard;

import dev.yanianz.atrium.module.display.TabResync;
import dev.yanianz.atrium.module.display.TabText;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
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
 * <p>Exclusivity ({@link TabSidebarSync}): while Atrium shows a sidebar, its scoreboard is the player's custom
 * scoreboard in TAB and TAB's scoreboard visibility is kept on, so TAB's own default scoreboards never appear.
 * While Atrium shows none, {@code tab-default-scoreboard} either hides TAB's scoreboards too or hands the slot
 * back to them. Atrium remembers each player's TAB visibility before it first changed it and restores it when it
 * stops using TAB.
 *
 * <p>Threading: {@link #update} and {@link #hide} run on the player's thread and only record what the player
 * should see (and wake the {@link TabResync} when that changed). {@link #flush} runs on the global thread and is
 * the only place that calls into TAB, so Atrium's calls are never concurrent and never on a region thread. TAB
 * forgets API scoreboards when it reloads and re-creates players when it loads them; its events reach the module
 * through the resync, which invalidates this backend and flushes right away.
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
        /** Atrium's board is the player's custom scoreboard in TAB (as far as Atrium knows). */
        boolean forced;
        /** Flush number of the last showScoreboard call; TAB applies it asynchronously. */
        long showFlush = Long.MIN_VALUE / 2;
    }

    private final Logger logger;
    private final TabResync resync;
    private final Supplier<TabSidebarSync.DefaultMode> mode;
    private final Map<UUID, Wanted> wanted = new ConcurrentHashMap<>();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    /** TAB's scoreboard visibility of each player before Atrium first changed it. Global thread only. */
    private final Map<UUID, Boolean> originalVisibility = new ConcurrentHashMap<>();
    /** Players who left; their scoreboard is removed on the next flush. */
    private final Queue<UUID> gone = new ConcurrentLinkedQueue<>();
    private final EventHandler<TabLoadEvent> onTabLoad;
    private final EventHandler<PlayerLoadEvent> onPlayerLoad;
    private final AtomicBoolean errorReported = new AtomicBoolean();
    private final AtomicLong reapplied = new AtomicLong();
    private long flushes;
    private volatile boolean listenersRegistered;
    private volatile boolean detached;

    private TabSidebarBackend(Logger logger, TabResync resync, Supplier<TabSidebarSync.DefaultMode> mode) {
        this.logger = logger;
        this.resync = resync;
        this.mode = mode;
        // TAB fires these on its own threads: only record them; the resync runs on Atrium's global thread.
        this.onTabLoad = event -> resync.reloaded();
        this.onPlayerLoad = event -> resync.playerLoaded(event.getPlayer().getUniqueId());
    }

    /** Whether TAB's scoreboard feature is running. Called only when TAB is enabled. */
    static boolean featureEnabled() {
        TabAPI api = TabAPI.getInstance();
        return api != null && api.getScoreboardManager() != null;
    }

    static @Nullable SidebarBackend create(Logger logger, TabResync resync,
            Supplier<TabSidebarSync.DefaultMode> mode) {
        TabAPI api = TabAPI.getInstance();
        if (api == null || api.getScoreboardManager() == null) {
            return null;
        }
        TabSidebarBackend backend = new TabSidebarBackend(logger, resync, mode);
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
        if (next.equals(previous)) {
            return 0;
        }
        resync.wake();
        if (previous == null || previous == HIDDEN) {
            return next.lines().size() + 1;
        }
        return TabSidebarPlan.plan(previous.title(), previous.lines(), next.title(), next.lines()).size();
    }

    @Override
    public void hide(Player player) {
        // Recorded for every rendered player: with tab-default-scoreboard: hide, TAB's own scoreboards must stay
        // hidden for players who never had an Atrium sidebar too.
        if (wanted.put(player.getUniqueId(), HIDDEN) != HIDDEN) {
            resync.wake();
        }
    }

    @Override
    public void forget(Player player) {
        UUID id = player.getUniqueId();
        wanted.remove(id);
        gone.add(id);
    }

    @Override
    public boolean usable() {
        return !detached && TabBackendFactory.pluginEnabled() && featureEnabled();
    }

    @Override
    public void invalidate(TabResync.Batch batch) {
        if (batch.all()) {
            // TAB dropped every API scoreboard on reload; create them again.
            states.clear();
            return;
        }
        for (UUID id : batch.players()) {
            State state = states.get(id);
            if (state != null) {
                state.forced = false; // TAB re-created the player: show Atrium's board again
            }
        }
    }

    @Override
    public void flush() {
        if (detached) {
            return;
        }
        TabAPI api = TabAPI.getInstance();
        ScoreboardManager manager = api == null ? null : api.getScoreboardManager();
        if (manager == null) {
            return; // the module notices through usable() and picks another backend
        }
        long flush = ++flushes;
        TabSidebarSync.DefaultMode defaults = mode.get();
        try {
            for (UUID id; (id = gone.poll()) != null; ) {
                originalVisibility.remove(id);
                State state = states.remove(id);
                if (state != null && state.board != null) {
                    remove(manager, state.board);
                }
            }
            for (Map.Entry<UUID, Wanted> entry : wanted.entrySet()) {
                TabPlayer player = api.getPlayer(entry.getKey());
                if (player == null || !player.isLoaded()) {
                    continue; // TAB loads players asynchronously; its PlayerLoadEvent triggers the next flush
                }
                apply(manager, player, entry.getKey(), entry.getValue(), defaults, flush);
            }
        } catch (IllegalStateException discarded) {
            // TAB was reloaded during this flush and discarded its manager; its TabLoadEvent starts over.
            states.clear();
        }
    }

    private void apply(ScoreboardManager manager, TabPlayer player, UUID id, Wanted next,
            TabSidebarSync.DefaultMode defaults, long flush) {
        State state = states.computeIfAbsent(id, key -> new State());
        try {
            boolean show = next != HIDDEN;
            if (show) {
                updateBoard(manager, id, state, next);
            }
            boolean pending = flush - state.showFlush < 2;
            boolean forced = state.forced && state.board != null && (pending || manager.hasCustomScoreboard(player));
            if (state.forced && !forced) {
                reapplied.incrementAndGet(); // TAB lost Atrium's board (or replaced it) without an event
            }
            state.forced = forced;
            boolean visible = manager.hasScoreboardVisible(player);
            Boolean original = originalVisibility.get(id);
            boolean hiddenByAtrium = original != null && original && !visible;
            TabSidebarSync.Step step = TabSidebarSync.plan(show, defaults, forced, visible, hiddenByAtrium);
            if (step.none()) {
                return;
            }
            Boolean visibility = step.visible();
            if (step.show()) {
                manager.showScoreboard(player, state.board);
                state.forced = true;
                state.showFlush = flush;
                if (Boolean.TRUE.equals(visibility)) {
                    // showScoreboard applies asynchronously; turning visibility on first would briefly show
                    // TAB's own scoreboard. The next flush turns it on once Atrium's board is the custom one.
                    visibility = null;
                }
            }
            if (visibility != null) {
                originalVisibility.putIfAbsent(id, visible);
                manager.setScoreboardVisible(player, visibility, false);
                if (visibility.equals(originalVisibility.get(id))) {
                    originalVisibility.remove(id); // back to the player's own TAB setting
                }
            }
            if (step.reset()) {
                release(manager, player, state);
                state.forced = false;
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

    private void updateBoard(ScoreboardManager manager, UUID id, State state, Wanted next) {
        if (state.board == null) {
            String name = boardName(id);
            Scoreboard leftover = manager.getRegisteredScoreboards().get(name);
            if (leftover != null) {
                remove(manager, leftover);
            }
            state.board = manager.createScoreboard(name, next.title(), next.lines());
            state.forced = false;
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
    }

    /** Hands the slot back only while Atrium's scoreboard is the player's custom one. */
    private static void release(ScoreboardManager manager, TabPlayer player, State state) {
        if (!manager.hasCustomScoreboard(player)) {
            return;
        }
        Scoreboard active = manager.getActiveScoreboard(player);
        // No active scoreboard: the player's TAB scoreboards are hidden; the custom one is still Atrium's.
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
    public @Nullable TabStatus tabStatus() {
        TabAPI api = detached ? null : TabAPI.getInstance();
        ScoreboardManager manager = api == null ? null : api.getScoreboardManager();
        if (manager == null) {
            return new TabStatus(0, 0, 0, reapplied.get());
        }
        int loaded = 0;
        int applied = 0;
        int hidden = 0;
        for (TabPlayer player : api.getOnlinePlayers()) {
            if (!player.isLoaded()) {
                continue;
            }
            loaded++;
            UUID id = player.getUniqueId();
            State state = states.get(id);
            boolean hiddenByAtrium;
            try {
                hiddenByAtrium = originalVisibility.containsKey(id) && !manager.hasScoreboardVisible(player);
            } catch (RuntimeException e) {
                hiddenByAtrium = false;
            }
            if (hiddenByAtrium) {
                hidden++;
            }
            if (hiddenByAtrium || (state != null && state.forced)) {
                applied++;
            }
        }
        return new TabStatus(loaded, applied, hidden, reapplied.get());
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
            if (manager != null) {
                restoreVisibility(api, manager);
                for (Map.Entry<UUID, State> entry : states.entrySet()) {
                    State state = entry.getValue();
                    TabPlayer player = api.getPlayer(entry.getKey());
                    if (state.forced && player != null && player.isLoaded()) {
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
            resync.close();
            wanted.clear();
            states.clear();
            gone.clear();
            originalVisibility.clear();
        }
    }

    /** Gives every player back the TAB scoreboard visibility they had before Atrium changed it. */
    private void restoreVisibility(TabAPI api, ScoreboardManager manager) {
        for (Map.Entry<UUID, Boolean> entry : originalVisibility.entrySet()) {
            TabPlayer player = api.getPlayer(entry.getKey());
            if (player == null || !player.isLoaded()) {
                continue;
            }
            try {
                if (manager.hasScoreboardVisible(player) != entry.getValue()) {
                    manager.setScoreboardVisible(player, entry.getValue(), false);
                }
            } catch (RuntimeException error) {
                logger.warn("Could not restore TAB's scoreboard visibility of {}", player.getName(), error);
            }
        }
    }

    @Override
    public void detach() {
        detached = true;
        resync.close();
        wanted.clear();
        states.clear();
        gone.clear();
        originalVisibility.clear();
    }

    @Override
    public String toString() {
        return "SidebarBackend[TAB]";
    }
}
