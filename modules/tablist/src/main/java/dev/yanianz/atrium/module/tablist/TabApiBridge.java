package dev.yanianz.atrium.module.tablist;

import dev.yanianz.atrium.api.service.ServiceRegistry;
import dev.yanianz.atrium.api.vanish.VanishService;
import dev.yanianz.atrium.module.display.TabResync;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.event.EventBus;
import me.neznamy.tab.api.event.EventHandler;
import me.neznamy.tab.api.event.player.PlayerLoadEvent;
import me.neznamy.tab.api.event.plugin.TabLoadEvent;
import me.neznamy.tab.api.integration.VanishIntegration;
import me.neznamy.tab.api.nametag.NameTagManager;
import me.neznamy.tab.api.tablist.HeaderFooterManager;
import me.neznamy.tab.api.tablist.SortingManager;
import me.neznamy.tab.api.tablist.TabListFormatManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The TAB engine: feeds Atrium's rendered tab list into the TAB plugin through its API. Only loaded when TAB is
 * enabled (see {@link TabBridgeFactory}); this is the only tablist class that references TAB.
 *
 * <p>Atrium keeps two maps: what it wants each player to see ({@code desired}, written from player threads) and
 * what it last handed to TAB ({@code pushed}, global thread). {@link #flush} only calls TAB for differences.
 *
 * <p>TAB forgets every API override when it loads a player (after the join, asynchronously) and when it reloads.
 * Its {@code PlayerLoadEvent} and {@code TabLoadEvent} feed a {@link TabResync}, which re-applies all of Atrium's
 * overrides on the global thread right away instead of on the next timer cycle. As a safety net every flush also
 * reads TAB's current list name and team back and hands Atrium's values over again when TAB lost them without an
 * event; header and footer have no read-back in TAB's API.
 *
 * <p>TAB's managers are looked up on every flush: TAB discards them on reload, and returns {@code null} for a
 * feature that is disabled in its config. Atrium then shows that part itself (see {@link TabOwnership}).
 */
final class TabApiBridge implements TabBridge {
    /** What TAB was last given for one player; {@code null} fields were not set by Atrium. */
    private record Pushed(@Nullable String header, @Nullable String footer, @Nullable String listName,
            @Nullable String team) {
        static final Pushed NONE = new Pushed(null, null, null, null);

        boolean empty() {
            return header == null && footer == null && listName == null && team == null;
        }
    }

    private final Logger logger;
    private final TabResync resync;
    private final Map<UUID, Frame> desired = new ConcurrentHashMap<>();
    private final Map<UUID, Pushed> pushed = new ConcurrentHashMap<>();
    /** Messages already logged; cleared when the condition goes away. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    /** Set by /tablist refresh: hand everything over again on the next flush. */
    private final AtomicBoolean resend = new AtomicBoolean();
    private final AtomicLong reapplied = new AtomicLong();
    private final EventHandler<TabLoadEvent> onTabLoad;
    private final EventHandler<PlayerLoadEvent> onPlayerLoad;
    private final AtriumVanish vanish;
    private volatile TablistCapabilities capabilities = TablistCapabilities.tab(false, false, false);
    private volatile boolean detached;
    private volatile boolean listenersRegistered;

    private TabApiBridge(Logger logger, ServiceRegistry services, TabResync resync) {
        this.logger = logger;
        this.resync = resync;
        this.vanish = new AtriumVanish(services);
        // TAB fires these on its own threads: only record them; the resync runs on Atrium's global thread.
        this.onTabLoad = event -> resync.reloaded();
        this.onPlayerLoad = event -> resync.playerLoaded(event.getPlayer().getUniqueId());
    }

    /** Called only when TAB is enabled. */
    static TabApiBridge create(Logger logger, ServiceRegistry services, TabResync resync) {
        TabAPI api = TabAPI.getInstance();
        if (api == null) {
            throw new IllegalStateException("the TAB API is not initialised");
        }
        TabApiBridge bridge = new TabApiBridge(logger, services, resync);
        bridge.capabilities = capabilitiesOf(api);
        EventBus bus = api.getEventBus();
        if (bus != null) {
            bus.register(TabLoadEvent.class, bridge.onTabLoad);
            bus.register(PlayerLoadEvent.class, bridge.onPlayerLoad);
            bridge.listenersRegistered = true;
        } else {
            logger.warn("TAB offers no event bus; after /tab reload Atrium's tab list returns on the next update");
        }
        try {
            bridge.vanish.register();
        } catch (RuntimeException e) {
            logger.warn("Could not tell TAB about Atrium's vanished players", e);
        }
        return bridge;
    }

    private static TablistCapabilities capabilitiesOf(TabAPI api) {
        return TablistCapabilities.tab(api.getHeaderFooterManager() != null, api.getTabListFormatManager() != null,
                api.getSortingManager() != null);
    }

    @Override
    public void offer(UUID player, Frame frame) {
        if (detached) {
            return;
        }
        Frame previous = desired.put(player, frame);
        if (!frame.equals(previous)) {
            resync.wake();
        }
    }

    @Override
    public void forget(UUID player) {
        desired.remove(player);
        pushed.remove(player);
    }

    @Override
    public void resendAll() {
        resend.set(true);
    }

    @Override
    public void invalidate(TabResync.Batch batch) {
        if (batch.all()) {
            pushed.clear();
        } else {
            pushed.keySet().removeAll(batch.players());
        }
    }

    @Override
    public boolean usable() {
        return !detached && TabBridgeFactory.pluginEnabled() && TabAPI.getInstance() != null;
    }

    @Override
    public TablistCapabilities capabilities() {
        return detached ? TablistCapabilities.tab(false, false, false) : capabilities;
    }

    @Override
    public void flush(@Nullable Map<UUID, String> teamNames) {
        if (detached) {
            return;
        }
        if (resend.getAndSet(false)) {
            pushed.clear();
        }
        TabAPI api = TabAPI.getInstance();
        if (api == null) {
            return;
        }
        HeaderFooterManager headers = api.getHeaderFooterManager();
        TabListFormatManager formats = api.getTabListFormatManager();
        SortingManager sorting = api.getSortingManager();
        TablistCapabilities now = TablistCapabilities.tab(headers != null, formats != null, sorting != null);
        if (!now.equals(capabilities)) {
            capabilities = now;
            // The module renders natively what TAB cannot show; it reads the new capabilities on its next render.
            pushed.clear();
        }
        report("layout", api.getLayoutManager() != null,
                "TAB's layout feature is on: it replaces the player list with its own slots, so Atrium's list names "
                        + "and order are only visible where the layout shows players");
        for (Map.Entry<UUID, Frame> entry : desired.entrySet()) {
            UUID id = entry.getKey();
            TabPlayer player = api.getPlayer(id);
            if (player == null || !player.isLoaded()) {
                continue; // TAB loads players asynchronously; its PlayerLoadEvent triggers the next flush
            }
            Pushed before = verified(player, pushed.getOrDefault(id, Pushed.NONE), formats, sorting);
            String team = teamNames == null ? null : teamNames.get(id);
            try {
                pushed.put(id, push(player, before, TabOverrides.of(entry.getValue()), team, headers, formats, sorting));
            } catch (IllegalStateException unloaded) {
                // TAB unloaded the player or discarded the feature (reload) while this ran: retried after its event.
                pushed.remove(id);
            } catch (RuntimeException error) {
                pushed.remove(id);
                if (reported.add("error:" + error.getClass().getName())) {
                    logger.warn("TAB rejected the tab list of {}; retrying on the next update", player.getName(), error);
                }
            }
        }
    }

    /**
     * Reads TAB's current list name and forced team back. Where TAB no longer holds what Atrium set (TAB rebuilt
     * the player without telling), the part counts as not pushed, so this flush hands it over again.
     */
    private Pushed verified(TabPlayer player, Pushed before, @Nullable TabListFormatManager formats,
            @Nullable SortingManager sorting) {
        if (before.empty()) {
            return before;
        }
        String listName = before.listName();
        String team = before.team();
        try {
            if (listName != null && formats != null && !("".equals(formats.getCustomPrefix(player))
                    && listName.equals(formats.getCustomName(player)) && "".equals(formats.getCustomSuffix(player)))) {
                listName = null;
                reapplied.incrementAndGet();
            }
            if (team != null && sorting != null && !team.equals(sorting.getForcedTeamName(player))) {
                team = null;
                reapplied.incrementAndGet();
            }
        } catch (RuntimeException unreadable) {
            return Pushed.NONE;
        }
        return listName == before.listName() && team == before.team()
                ? before
                : new Pushed(before.header(), before.footer(), listName, team);
    }

    private static Pushed push(TabPlayer player, Pushed before, TabOverrides values, @Nullable String team,
            @Nullable HeaderFooterManager headers, @Nullable TabListFormatManager formats,
            @Nullable SortingManager sorting) {
        String header = null;
        String footer = null;
        if (headers != null) {
            // Never null while Atrium owns it: "" hides TAB's header instead of falling back to it.
            if (!values.header().equals(before.header()) || !values.footer().equals(before.footer())) {
                headers.setHeaderAndFooter(player, values.header(), values.footer());
            }
            header = values.header();
            footer = values.footer();
        }
        String listName = null;
        if (formats != null) {
            if (values.managesListName()) {
                if (before.listName() == null) {
                    // Atrium's list name is the whole name; TAB's group prefix and suffix would double it.
                    formats.setPrefix(player, values.prefix());
                    formats.setSuffix(player, values.suffix());
                }
                if (!values.name().equals(before.listName())) {
                    formats.setName(player, values.name());
                }
                listName = values.name();
            } else if (before.listName() != null) {
                resetFormat(formats, player);
            }
        }
        String forcedTeam = null;
        if (sorting != null) {
            if (!Objects.equals(team, before.team())) {
                sorting.forceTeamName(player, team);
            }
            forcedTeam = team;
        }
        return new Pushed(header, footer, listName, forcedTeam);
    }

    private static void resetFormat(TabListFormatManager formats, TabPlayer player) {
        formats.setPrefix(player, TabOverrides.NO_OVERRIDE);
        formats.setName(player, TabOverrides.NO_OVERRIDE);
        formats.setSuffix(player, TabOverrides.NO_OVERRIDE);
    }

    private void report(String key, boolean condition, String message) {
        if (!condition) {
            reported.remove(key);
        } else if (reported.add(key)) {
            logger.info(message);
        }
    }

    @Override
    public Status status() {
        TabAPI api = detached ? null : TabAPI.getInstance();
        if (api == null) {
            return new Status(0, 0, reapplied.get(), false);
        }
        int loaded = 0;
        int applied = 0;
        for (TabPlayer player : api.getOnlinePlayers()) {
            if (!player.isLoaded()) {
                continue;
            }
            loaded++;
            Pushed state = pushed.get(player.getUniqueId());
            if (state != null && !state.empty()) {
                applied++;
            }
        }
        return new Status(loaded, applied, reapplied.get(), api.getLayoutManager() != null);
    }

    @Override
    public List<DebugRow> debug(UUID id) {
        TabAPI api = detached ? null : TabAPI.getInstance();
        TabPlayer player = api == null ? null : api.getPlayer(id);
        if (player == null || !player.isLoaded()) {
            return List.of();
        }
        Pushed state = pushed.getOrDefault(id, Pushed.NONE);
        HeaderFooterManager headers = api.getHeaderFooterManager();
        TabListFormatManager formats = api.getTabListFormatManager();
        SortingManager sorting = api.getSortingManager();
        NameTagManager nametags = api.getNameTagManager();
        List<DebugRow> rows = new ArrayList<>();
        // TAB's API has no getter for the header and footer it shows.
        rows.add(new DebugRow("header", headers == null ? null : state.header(), null, null, false));
        rows.add(new DebugRow("footer", headers == null ? null : state.footer(), null, null, false));
        String prefix = state.listName() == null ? null : "";
        rows.add(row("tab-prefix", prefix, formats, player, TabListFormatManager::getCustomPrefix,
                TabListFormatManager::getOriginalRawPrefix));
        rows.add(row("tab-name", state.listName(), formats, player, TabListFormatManager::getCustomName,
                TabListFormatManager::getOriginalRawName));
        rows.add(row("tab-suffix", prefix, formats, player, TabListFormatManager::getCustomSuffix,
                TabListFormatManager::getOriginalRawSuffix));
        rows.add(row("sorting-team", state.team(), sorting, player, SortingManager::getForcedTeamName,
                SortingManager::getOriginalTeamName));
        // Atrium has no nametag configuration: these show what TAB draws above the head.
        rows.add(row("nametag-prefix", null, nametags, player, NameTagManager::getCustomPrefix,
                NameTagManager::getOriginalRawPrefix));
        rows.add(row("nametag-suffix", null, nametags, player, NameTagManager::getCustomSuffix,
                NameTagManager::getOriginalRawSuffix));
        return rows;
    }

    private interface Getter<M> {
        @Nullable String get(M manager, TabPlayer player);
    }

    private static <M> DebugRow row(String part, @Nullable String atrium, @Nullable M manager, TabPlayer player,
            Getter<M> current, Getter<M> configured) {
        if (manager == null) {
            return new DebugRow(part + " (TAB feature off)", null, null, null, false);
        }
        Function<Getter<M>, String> read = getter -> {
            try {
                return getter.get(manager, player);
            } catch (RuntimeException e) {
                return "<error: " + e.getClass().getSimpleName() + ">";
            }
        };
        return new DebugRow(part, atrium, read.apply(current), read.apply(configured), true);
    }

    @Override
    public void stop() {
        if (detached) {
            return;
        }
        detached = true;
        try {
            TabAPI api = TabBridgeFactory.pluginEnabled() ? TabAPI.getInstance() : null;
            if (api != null) {
                resetAll(api);
                EventBus bus = api.getEventBus();
                if (bus != null && listenersRegistered) {
                    bus.unregister(onTabLoad);
                    bus.unregister(onPlayerLoad);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            logger.warn("Removing Atrium's tab list from TAB did not finish cleanly", error);
        } finally {
            unregisterVanish();
            desired.clear();
            pushed.clear();
        }
    }

    /** Resets only what Atrium set; TAB then shows its own configuration again. */
    private void resetAll(TabAPI api) {
        HeaderFooterManager headers = api.getHeaderFooterManager();
        TabListFormatManager formats = api.getTabListFormatManager();
        SortingManager sorting = api.getSortingManager();
        for (Map.Entry<UUID, Pushed> entry : pushed.entrySet()) {
            Pushed state = entry.getValue();
            TabPlayer player = api.getPlayer(entry.getKey());
            if (state.empty() || player == null || !player.isLoaded()) {
                continue;
            }
            try {
                if (headers != null && (state.header() != null || state.footer() != null)) {
                    headers.setHeaderAndFooter(player, null, null);
                }
                if (formats != null && state.listName() != null) {
                    resetFormat(formats, player);
                }
                if (sorting != null && state.team() != null) {
                    sorting.forceTeamName(player, null);
                }
            } catch (RuntimeException error) {
                logger.warn("Could not hand the tab list of {} back to TAB", player.getName(), error);
            }
        }
    }

    @Override
    public void detach() {
        detached = true;
        desired.clear();
        pushed.clear();
        unregisterVanish();
    }

    private void unregisterVanish() {
        try {
            vanish.unregister();
        } catch (RuntimeException | LinkageError error) {
            logger.debug("Unregistering Atrium's TAB vanish integration failed", error);
        }
    }

    /**
     * Tells TAB which players Atrium's vanish module hides, so TAB's own features (its online counts, layout and
     * nametags) agree with Atrium. Answers "not vanished" while the vanish module is off. TAB calls this from its
     * own thread; {@link VanishService} is thread-safe.
     */
    private static final class AtriumVanish extends VanishIntegration {
        private final ServiceRegistry services;

        AtriumVanish(ServiceRegistry services) {
            super("Atrium");
            this.services = services;
        }

        @Override
        public boolean isVanished(TabPlayer player) {
            Optional<VanishService> service = services.get(VanishService.class);
            return service.isPresent() && service.get().isVanished(player.getUniqueId());
        }

        @Override
        public boolean canSee(TabPlayer viewer, TabPlayer target) {
            Optional<VanishService> service = services.get(VanishService.class);
            if (service.isEmpty()) {
                return true;
            }
            Player viewing = Bukkit.getPlayer(viewer.getUniqueId());
            Player seen = Bukkit.getPlayer(target.getUniqueId());
            return viewing == null || seen == null || service.get().canSee(viewing, seen);
        }
    }
}
