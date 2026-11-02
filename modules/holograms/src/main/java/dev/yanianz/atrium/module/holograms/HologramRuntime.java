package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import dev.yanianz.atrium.core.scheduler.TaskHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntBinaryOperator;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The live entities of one hologram at one fixed location. A move creates a new runtime and retires the
 * old one, so every entity operation of a runtime happens on the single region that owns its location.
 *
 * <p>Threads: methods marked "region" run on the region thread of {@link #location}; they are
 * {@code synchronized} so state handed between region threads (Folia region merges/splits) stays consistent.
 * The viewer tracker reads the published {@link Snapshot} and the volatile fields from player threads.
 *
 * <p>Counters (page timer, text refresh) belong to the hologram and advance once per hologram timer run,
 * never once per viewer, so timing does not depend on how many players watch.
 */
final class HologramRuntime {
    /** Entities as seen by viewer threads. Immutable. */
    record Snapshot(boolean spawned, List<Entity> shared, @Nullable Entity interaction, Map<UUID, List<Entity>> personal,
            boolean sharedHidden, boolean interactionHidden) {
        static final Snapshot EMPTY = new Snapshot(false, List.of(), null, Map.of(), false, false);
    }

    private final Displays displays;
    final String key;
    final StoredLocation location;

    private volatile Hologram definition;
    private volatile List<Layout> layouts;
    private volatile boolean hasTokens;
    private volatile boolean hasViewerTag;
    private final AtomicLong version = new AtomicLong();
    private final Set<UUID> reserved = ConcurrentHashMap.newKeySet();
    private final Object reserveLock = new Object();
    private final Map<UUID, Integer> personalPages = new ConcurrentHashMap<>();
    private volatile int sharedPageView;
    private volatile boolean retired;
    private volatile Snapshot snapshot = Snapshot.EMPTY;

    // ---- region state, guarded by this ----
    private boolean spawned;
    private @Nullable Location base;
    private final View shared = new View();
    private @Nullable Interaction interaction;
    private Layout.@Nullable Box interactionBox;
    private final Map<UUID, View> personalViews = new HashMap<>();
    private int sharedPage;
    private long ticks;
    private long lastPageTick;
    private long lastTextTick;
    private boolean renderedPersonalMode;
    private @Nullable TaskHandle timer;

    HologramRuntime(Displays displays, Hologram definition) {
        this.displays = displays;
        this.key = definition.key();
        this.location = definition.location();
        define(definition, false);
    }

    // ---- definition (any thread) ----

    Hologram definition() {
        return definition;
    }

    List<Layout> layouts() {
        return layouts;
    }

    Snapshot snapshot() {
        return snapshot;
    }

    long version() {
        return version.get();
    }

    boolean retired() {
        return retired;
    }

    /** Applies an edited definition (same location) and re-renders on the region thread. */
    void update(Hologram next) {
        define(next, true);
    }

    private void define(Hologram next, boolean rebuild) {
        boolean tokens = false;
        boolean viewer = false;
        for (List<HoloLine> page : next.pages()) {
            for (HoloLine line : page) {
                if (line.content() instanceof LineContent.Text(String template)) {
                    tokens |= PlaceholderText.hasTokens(template);
                    viewer |= PlaceholderText.hasViewerTag(template);
                }
            }
        }
        List<Layout> computed = new ArrayList<>();
        HologramSettings s = next.settings();
        Layout.Metrics metrics = displays.metrics();
        for (List<HoloLine> page : next.pages()) {
            computed.add(Layout.of(page, s.scale(), s.lineSpacing(), s.lineWidth(), metrics, displays.renderer()::visibleChars));
        }
        this.layouts = List.copyOf(computed);
        this.hasTokens = tokens;
        this.hasViewerTag = viewer;
        this.definition = next;
        version.incrementAndGet();
        if (rebuild) {
            onRegion(this::rebuild);
        }
    }

    /** Each viewer gets own entities: forced ON, or AUTO with {@code <viewer>} / PlaceholderAPI placeholders. */
    boolean personalMode() {
        return switch (definition.settings().perViewer()) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> hasViewerTag || (hasTokens && displays.renderer().papiEnabled());
        };
    }

    /** Text that changes over time (PlaceholderAPI values). */
    boolean dynamicText() {
        return hasTokens && displays.renderer().papiEnabled();
    }

    boolean clickable() {
        Hologram d = definition;
        return !d.actions().isEmpty() || (d.settings().clickPages() && d.pages().size() > 1);
    }

    Optional<Location> resolveBase() {
        return location.toLocation();
    }

    /** Runs on the region of the location: inline when already there, else scheduled. */
    private void onRegion(Runnable task) {
        Optional<Location> at = resolveBase();
        if (at.isEmpty()) {
            return; // world not loaded: nothing is spawned there
        }
        if (Bukkit.isOwnedByCurrentRegion(at.get())) {
            task.run();
            return;
        }
        try {
            displays.scheduler().region(at.get(), task);
        } catch (RuntimeException e) {
            // Only happens while the plugin is disabling; the entities are non-persistent and vanish with the chunk.
            displays.logger().warn("Could not schedule hologram {} on its region: {}", key, e.toString());
        }
    }

    // ---- lifecycle ----

    /** Spawns when the chunk is loaded (any thread). Otherwise the chunk load event spawns it later. */
    void scheduleSpawn() {
        onRegion(this::spawnIfLoaded);
    }

    /** Any thread: spawns one tick later, after a chunk load event settled (block and entity data loaded). */
    void scheduleSpawnSoon() {
        Optional<Location> at = resolveBase();
        if (at.isPresent() && !retired) {
            displays.scheduler().regionLater(at.get(), this::spawnIfLoaded, 1);
        }
    }

    private void tag(Entity entity) {
        displays.tag(entity, key);
    }

    /** Region. */
    synchronized void spawnIfLoaded() {
        if (retired || spawned) {
            return;
        }
        Optional<Location> at = resolveBase();
        if (at.isEmpty()) {
            return;
        }
        Location loc = at.get();
        World world = loc.getWorld();
        int cx = loc.getBlockX() >> 4;
        int cz = loc.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) {
            return;
        }
        Chunk chunk = world.getChunkAt(cx, cz);
        if (!chunk.isEntitiesLoaded()) {
            return;
        }
        displays.sweepOrphans(chunk, key);
        base = loc;
        spawned = true;
        ticks = 0;
        lastPageTick = 0;
        lastTextTick = 0;
        sharedPage = Pages.clamp(sharedPage, layouts.size());
        sharedPageView = sharedPage;
        renderShared();
        updateInteraction();
        int period = displays.tickPeriod();
        timer = displays.track(displays.scheduler().regionTimer(loc, this::tick, period, period));
        version.incrementAndGet();
        publish();
        displays.nudgeViewers(this);
    }

    /** Region. Removes every entity; called on chunk unload, delete, move and disable. */
    synchronized void despawn() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (!spawned) {
            return;
        }
        shared.remove();
        removeInteraction();
        personalViews.values().forEach(View::remove);
        personalViews.clear();
        spawned = false;
        version.incrementAndGet();
        publish();
    }

    /** Any thread. The runtime is no longer used; its entities are removed on its region. */
    void retire() {
        retired = true;
        synchronized (reserveLock) {
            reserved.clear();
        }
        personalPages.clear();
        onRegion(this::despawn);
    }

    /** Region: re-render after an edit. */
    private synchronized void rebuild() {
        if (retired) {
            return;
        }
        if (!spawned) {
            spawnIfLoaded();
            return;
        }
        boolean respawned = renderShared();
        respawned |= updateInteraction();
        Iterator<Map.Entry<UUID, View>> it = personalViews.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, View> entry = it.next();
            if (!personalMode() || !reserved.contains(entry.getKey())) {
                entry.getValue().remove();
                it.remove();
            }
        }
        publish();
        if (respawned) {
            displays.nudgeViewers(this);
        }
    }

    /** Region timer: page timer, text refresh and self-repair (e.g. after /kill). */
    private synchronized void tick() {
        if (!spawned || retired) {
            return;
        }
        ticks += displays.tickPeriod();
        HologramSettings s = definition.settings();
        int pages = layouts.size();
        boolean render = !shared.valid() || renderedPersonalMode != personalMode();
        if (s.pageInterval() > 0 && pages > 1 && ticks - lastPageTick >= s.pageInterval()) {
            lastPageTick = ticks;
            sharedPage = Pages.next(sharedPage, pages);
            sharedPageView = sharedPage;
            personalPages.replaceAll((uuid, page) -> Pages.next(page, pages));
            version.incrementAndGet(); // personal viewers re-render their next page
            render = true;
        } else if (dynamicText() && s.updateInterval() > 0 && ticks - lastTextTick >= s.updateInterval()) {
            render = true;
        }
        boolean respawned = false;
        if (render) {
            respawned = renderShared();
        }
        if (clickable() && (interaction == null || !interaction.isValid())) {
            respawned |= updateInteraction();
        }
        boolean lost = false;
        Iterator<Map.Entry<UUID, View>> it = personalViews.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, View> entry = it.next();
            if (!entry.getValue().valid() || !reserved.contains(entry.getKey())) {
                entry.getValue().remove();
                it.remove();
                lost = true;
            }
        }
        if (lost) {
            version.incrementAndGet(); // the viewer re-renders and gets a fresh copy
        }
        if (render || respawned || lost) {
            publish();
        }
        if (respawned) {
            displays.nudgeViewers(this);
        }
    }

    /** Region. Returns true when entities were respawned. */
    private boolean renderShared() {
        Hologram d = definition;
        List<Layout> ls = layouts;
        sharedPage = Pages.clamp(sharedPage, ls.size());
        sharedPageView = sharedPage;
        Layout layout = ls.get(sharedPage);
        List<Component> texts = new ArrayList<>();
        for (Layout.TextElement text : layout.textElements()) {
            texts.add(displays.renderer().render(text.lines(), null));
        }
        boolean personal = personalMode();
        renderedPersonalMode = personal;
        lastTextTick = ticks;
        boolean hidden = personal || !d.settings().permission().isEmpty();
        return shared.apply(base.clone(), layout, texts, d.settings(), !hidden, this::tag, displays.logger());
    }

    /** Region. Returns true when the interaction entity was (re)spawned. */
    private boolean updateInteraction() {
        if (!clickable()) {
            removeInteraction();
            return false;
        }
        HologramSettings s = definition.settings();
        Layout.Box box = Layout.interactionBox(layouts, s.interactionWidth(), s.interactionHeight(),
                displays.metrics().padding());
        boolean hidden = !s.permission().isEmpty() || !s.clickPermission().isEmpty();
        if (interaction != null && interaction.isValid() && interactionBox != null
                && interactionBox.yOffset() == box.yOffset() && interaction.isVisibleByDefault() == !hidden) {
            interaction.setInteractionWidth(box.width());
            interaction.setInteractionHeight(box.height());
            interactionBox = box;
            return false;
        }
        removeInteraction();
        Location at = base.clone().add(0, box.yOffset(), 0);
        interaction = at.getWorld().spawn(at, Interaction.class, entity -> {
            entity.setPersistent(false);
            tag(entity);
            entity.setInteractionWidth(box.width());
            entity.setInteractionHeight(box.height());
            entity.setResponsive(true);
            entity.setVisibleByDefault(!hidden);
        });
        interactionBox = box;
        displays.registerInteraction(interaction.getUniqueId(), key);
        return true;
    }

    private void removeInteraction() {
        if (interaction != null) {
            displays.unregisterInteraction(interaction.getUniqueId());
            if (interaction.isValid()) {
                interaction.remove();
            }
            interaction = null;
            interactionBox = null;
        }
    }

    private void publish() {
        if (!spawned) {
            snapshot = Snapshot.EMPTY;
            return;
        }
        Map<UUID, List<Entity>> personal = new HashMap<>();
        personalViews.forEach((uuid, view) -> personal.put(uuid, view.entities()));
        HologramSettings s = definition.settings();
        snapshot = new Snapshot(true, shared.entities(), interaction, Map.copyOf(personal),
                renderedPersonalMode || !s.permission().isEmpty(),
                !s.permission().isEmpty() || !s.clickPermission().isEmpty());
    }

    // ---- shared page (any thread) ----

    /** Changes the page everyone without an own copy sees. {@code op(current, count)}. */
    void changeSharedPage(IntBinaryOperator op) {
        onRegion(() -> {
            synchronized (this) {
                if (!spawned || retired) {
                    return;
                }
                int count = layouts.size();
                sharedPage = Pages.clamp(op.applyAsInt(sharedPage, count), count);
                sharedPageView = sharedPage;
                boolean respawned = renderShared();
                publish();
                if (respawned) {
                    displays.nudgeViewers(this);
                }
            }
        });
    }

    // ---- personal copies ----

    boolean hasPersonal(UUID viewer) {
        return reserved.contains(viewer);
    }

    /** Player thread: claims a personal copy, unless the per-hologram cap is reached. */
    boolean tryReserve(UUID viewer, int cap) {
        if (retired) {
            return false;
        }
        synchronized (reserveLock) {
            if (reserved.contains(viewer)) {
                return true;
            }
            if (reserved.size() >= cap) {
                return false;
            }
            reserved.add(viewer);
            personalPages.putIfAbsent(viewer, sharedPageView);
            return true;
        }
    }

    /** Any thread: frees the personal copy and removes its entities. */
    void release(UUID viewer) {
        boolean had;
        synchronized (reserveLock) {
            had = reserved.remove(viewer);
        }
        personalPages.remove(viewer);
        if (had) {
            onRegion(() -> removePersonalView(viewer));
        }
    }

    private synchronized void removePersonalView(UUID viewer) {
        View view = personalViews.remove(viewer);
        if (view != null) {
            view.remove();
            publish();
        }
    }

    int personalPage(UUID viewer) {
        return personalPages.getOrDefault(viewer, sharedPageView);
    }

    void setPersonalPage(UUID viewer, int page) {
        if (reserved.contains(viewer)) {
            personalPages.put(viewer, Pages.clamp(page, layouts.size()));
        }
    }

    /**
     * Viewer's thread: renders the text for this viewer (PlaceholderAPI runs here, on the player's own thread)
     * and hands the components to the region thread, which creates or updates the entities.
     */
    void renderPersonal(Player viewer) {
        UUID uuid = viewer.getUniqueId();
        List<Layout> ls = layouts;
        Layout layout = ls.get(Pages.clamp(personalPage(uuid), ls.size()));
        List<Component> texts = new ArrayList<>();
        for (Layout.TextElement text : layout.textElements()) {
            texts.add(displays.renderer().render(text.lines(), viewer));
        }
        onRegion(() -> applyPersonal(uuid, layout, texts));
    }

    /** Region. */
    private synchronized void applyPersonal(UUID viewer, Layout layout, List<Component> texts) {
        if (!spawned || retired || !reserved.contains(viewer) || texts.size() != layout.textElementCount()) {
            return;
        }
        View view = personalViews.computeIfAbsent(viewer, uuid -> new View());
        boolean respawned = view.apply(base.clone(), layout, texts, definition.settings(), false, this::tag,
                displays.logger());
        if (respawned || !snapshot.personal().containsKey(viewer)) {
            publish();
            displays.nudge(viewer);
        }
    }
}
