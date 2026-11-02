package dev.yanianz.atrium.module.tablist;

import dev.yanianz.atrium.api.service.ServiceRegistry;
import dev.yanianz.atrium.api.vanish.VanishService;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
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
import me.neznamy.tab.api.integration.VanishIntegration;
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
 * TAB forgets API overrides when it reloads or reloads a player, so its {@code TabLoadEvent} and
 * {@code PlayerLoadEvent} mark that state as gone and the next flush hands it over again.
 *
 * <p>TAB's managers are looked up on every flush: TAB discards them on reload, and returns {@code null} for a
 * feature that is disabled in its config. Such a part is then left to TAB and logged once.
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
    private final Map<UUID, Frame> desired = new ConcurrentHashMap<>();
    private final Map<UUID, Pushed> pushed = new ConcurrentHashMap<>();
    /** Parts left to TAB that were already logged; cleared when the TAB feature comes back. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    /** Set from TAB's thread: TAB reloaded, every override is gone. */
    private final AtomicBoolean tabReloaded = new AtomicBoolean();
    /** Set by /tablist refresh: hand everything over again on the next flush. */
    private final AtomicBoolean resend = new AtomicBoolean();
    /** Filled from TAB's thread: these players were (re)loaded by TAB and lost their overrides. */
    private final Queue<UUID> reloadedPlayers = new ConcurrentLinkedQueue<>();
    private final EventHandler<TabLoadEvent> onTabLoad = event -> tabReloaded.set(true);
    private final EventHandler<PlayerLoadEvent> onPlayerLoad = event -> reloadedPlayers.add(event.getPlayer().getUniqueId());
    private final AtriumVanish vanish;
    private volatile boolean detached;
    private volatile boolean listenersRegistered;

    private TabApiBridge(Logger logger, ServiceRegistry services) {
        this.logger = logger;
        this.vanish = new AtriumVanish(services);
    }

    /** Called only when TAB is enabled. */
    static TabApiBridge create(Logger logger, ServiceRegistry services) {
        TabAPI api = TabAPI.getInstance();
        if (api == null) {
            throw new IllegalStateException("the TAB API is not initialised");
        }
        TabApiBridge bridge = new TabApiBridge(logger, services);
        EventBus bus = api.getEventBus();
        if (bus != null) {
            bus.register(TabLoadEvent.class, bridge.onTabLoad);
            bus.register(PlayerLoadEvent.class, bridge.onPlayerLoad);
            bridge.listenersRegistered = true;
        } else {
            logger.warn("TAB offers no event bus; after /tab reload use /tablist refresh to hand the tab list over again");
        }
        try {
            bridge.vanish.register();
        } catch (RuntimeException e) {
            logger.warn("Could not tell TAB about Atrium's vanished players", e);
        }
        return bridge;
    }

    @Override
    public void offer(UUID player, Frame frame) {
        if (!detached) {
            desired.put(player, frame);
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
    public boolean usable() {
        return !detached && TabBridgeFactory.pluginEnabled() && TabAPI.getInstance() != null;
    }

    @Override
    public TablistCapabilities capabilities() {
        TabAPI api = detached ? null : TabAPI.getInstance();
        if (api == null) {
            return TablistCapabilities.tab(false, false, false);
        }
        return TablistCapabilities.tab(api.getHeaderFooterManager() != null, api.getTabListFormatManager() != null,
                api.getSortingManager() != null);
    }

    @Override
    public void flush(@Nullable Map<UUID, String> teamNames) {
        if (detached) {
            return;
        }
        boolean reloaded = tabReloaded.getAndSet(false);
        if (resend.getAndSet(false) || reloaded) {
            pushed.clear();
        }
        for (UUID id; (id = reloadedPlayers.poll()) != null; ) {
            pushed.remove(id);
        }
        TabAPI api = TabAPI.getInstance();
        if (api == null) {
            return;
        }
        HeaderFooterManager headers = api.getHeaderFooterManager();
        TabListFormatManager formats = api.getTabListFormatManager();
        SortingManager sorting = api.getSortingManager();
        boolean namesWanted = false;
        for (Frame frame : desired.values()) {
            if (frame.listName() != null) {
                namesWanted = true;
                break;
            }
        }
        reportMissing("header-footer", headers == null && !desired.isEmpty(),
                "TAB's header/footer feature is disabled in TAB's config; the header and footer are left to TAB");
        reportMissing("list-names", formats == null && namesWanted,
                "TAB's tablist name formatting is disabled in TAB's config; list names (and nicknames in the tab "
                        + "list) are left to TAB");
        reportMissing("sorting", sorting == null && teamNames != null,
                "TAB's sorting is disabled in TAB's config; the tab list order is left to TAB");
        for (Map.Entry<UUID, Frame> entry : desired.entrySet()) {
            UUID id = entry.getKey();
            TabPlayer player = api.getPlayer(id);
            if (player == null || !player.isLoaded()) {
                continue; // TAB loads players asynchronously; retried on the next flush
            }
            Pushed before = pushed.getOrDefault(id, Pushed.NONE);
            String team = teamNames == null ? null : teamNames.get(id);
            try {
                pushed.put(id, push(player, before, entry.getValue(), team, headers, formats, sorting));
            } catch (IllegalStateException discarded) {
                // TAB was reloaded while this flush ran and discarded its managers: start over next time.
                tabReloaded.set(true);
                return;
            } catch (RuntimeException error) {
                if (reported.add("error:" + error.getClass().getName())) {
                    logger.warn("TAB rejected the tab list of {}; retrying on the next update", player.getName(), error);
                }
            }
        }
    }

    private Pushed push(TabPlayer player, Pushed before, Frame frame, @Nullable String team,
            @Nullable HeaderFooterManager headers, @Nullable TabListFormatManager formats,
            @Nullable SortingManager sorting) {
        String header = null;
        String footer = null;
        if (headers != null) {
            if (!frame.header().equals(before.header()) || !frame.footer().equals(before.footer())) {
                headers.setHeaderAndFooter(player, frame.header(), frame.footer());
            }
            header = frame.header();
            footer = frame.footer();
        }
        String listName = null;
        if (formats != null) {
            if (frame.listName() != null) {
                if (before.listName() == null) {
                    // Atrium's list name format is the whole name; TAB's own prefix and suffix would double it.
                    formats.setPrefix(player, "");
                    formats.setSuffix(player, "");
                }
                if (!frame.listName().equals(before.listName())) {
                    formats.setName(player, frame.listName());
                }
                listName = frame.listName();
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
        formats.setPrefix(player, null);
        formats.setName(player, null);
        formats.setSuffix(player, null);
    }

    private void reportMissing(String part, boolean missing, String message) {
        if (!missing) {
            reported.remove(part);
            return;
        }
        if (reported.add(part)) {
            logger.info(message);
        }
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
                // After a TAB reload the overrides are already gone; nothing is left to reset.
                if (!tabReloaded.get()) {
                    resetAll(api);
                }
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
