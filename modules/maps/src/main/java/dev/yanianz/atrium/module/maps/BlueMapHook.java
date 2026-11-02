package dev.yanianz.atrium.module.maps;

import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.World;

/**
 * BlueMap integration: one {@link MarkerSet} per BlueMap map, POI markers for warps and the spawn
 * point, {@link ShapeMarker} for a spawn area and {@link ExtrudeMarker} for portal regions.
 *
 * <p>The API is reached through {@link BlueMapAPI#onEnable(Consumer)} / {@link BlueMapAPI#onDisable(Consumer)}
 * instead of a hard plugin dependency. The two listeners are registered exactly once, in the
 * constructor, and unregistered in {@link #shutdown()}: registering them from inside the enable
 * callback re-adds a listener on every BlueMap reload, which then fires twice for the same event.
 * They must never be re-registered per refresh.
 *
 * <p>Markers are diffed per BlueMap map id, so the same marker set is never cleared and rebuilt, and
 * the state map is keyed by string ids rather than by world objects: a world that unloads no longer
 * stays referenced by this hook.
 *
 * <p>Every method that touches the BlueMap API runs on the global region thread. The callbacks only
 * publish the API reference; the refresh they request happens on the global thread afterwards.
 */
final class BlueMapHook implements MapHook {
    private static final String DEFAULT_SET_ID = "atrium_warps";
    private static final Color LINE_COLOR = new Color(85, 255, 85);
    private static final Color FILL_COLOR = new Color(85, 255, 85, 0.35f);

    private final ModuleContext ctx;
    private final ConfigHandle<MapsConfig> config;
    private final Runnable onApiAvailable;
    private final Consumer<BlueMapAPI> enableListener;
    private final Consumer<BlueMapAPI> disableListener;
    /** BlueMap map id → marker id → marker last placed on that map. */
    private final Map<String, Map<String, MapMarker>> appliedByMap = new ConcurrentHashMap<>();
    /** Marker set ids this hook created, so disable can remove all of them, not just the current one. */
    private final Set<String> ownedSetIds = ConcurrentHashMap.newKeySet();
    private volatile BlueMapAPI api;
    private volatile State state = State.PENDING;
    private volatile int markerCount;
    private volatile String activeSetId;

    BlueMapHook(ModuleContext ctx, ConfigHandle<MapsConfig> config, Runnable onApiAvailable) {
        this.ctx = ctx;
        this.config = config;
        this.onApiAvailable = onApiAvailable;
        this.enableListener = this::onBlueMapEnable;
        this.disableListener = this::onBlueMapDisable;
        BlueMapAPI.onEnable(enableListener);
        try {
            BlueMapAPI.onDisable(disableListener);
        } catch (RuntimeException e) {
            BlueMapAPI.unregisterListener(enableListener);
            throw e;
        }
    }

    @Override
    public String id() {
        return "bluemap";
    }

    @Override
    public String displayName() {
        return "BlueMap";
    }

    @Override
    public State state() {
        return state;
    }

    /**
     * Called while BlueMap holds its own class lock: only publish the reference and ask the module
     * for a refresh, which is queued on the global thread.
     */
    private void onBlueMapEnable(BlueMapAPI instance) {
        api = instance;
        try {
            onApiAvailable.run();
        } catch (RuntimeException e) {
            ctx.logger().warn("Cannot schedule the BlueMap marker refresh", e);
        }
    }

    private void onBlueMapDisable(BlueMapAPI instance) {
        // BlueMap is going away; the marker sets go with it. Drop our bookkeeping so a later enable
        // starts from an empty diff instead of leaving stale markers behind.
        api = null;
        appliedByMap.clear();
        ownedSetIds.clear();
        activeSetId = null;
        markerCount = 0;
        state = State.PENDING;
    }

    private BlueMapAPI resolve() {
        BlueMapAPI current = api;
        if (current != null) {
            return current;
        }
        current = BlueMapAPI.getInstance().orElse(null);
        if (current != null) {
            api = current;
        }
        return current;
    }

    @Override
    public void apply(Collection<MapMarker> markers) {
        BlueMapAPI current = resolve();
        if (current == null) {
            state = State.PENDING;
            markerCount = 0;
            return;
        }
        MapsConfig cfg = config.get();
        String setId = MapText.sanitizeId(cfg.bluemapMarkerSetId, DEFAULT_SET_ID);
        String label = MapText.escapeHtml(cfg.markerSetLabel);
        if (activeSetId != null && !activeSetId.equals(setId)) {
            // The marker set id changed on reload: drop the old sets instead of leaving a ghost layer.
            removeOwnedSets(current);
            appliedByMap.clear();
        }
        activeSetId = setId;

        int placed = 0;
        Set<String> visitedMaps = new LinkedHashSet<>();
        try {
            for (Map.Entry<String, List<MapMarker>> entry : MarkerGeometry.byWorld(markers).entrySet()) {
                World bukkitWorld = Bukkit.getWorld(entry.getKey());
                if (bukkitWorld == null) {
                    continue;
                }
                BlueMapWorld world = resolveWorld(current, bukkitWorld, entry.getKey());
                if (world == null) {
                    continue;
                }
                Map<String, MapMarker> next = MarkerDiff.index(entry.getValue());
                for (BlueMapMap map : world.getMaps()) {
                    String mapId = map.getId();
                    MarkerSet set = map.getMarkerSets().get(setId);
                    if (set == null) {
                        set = MarkerSet.builder()
                                .label(label)
                                .toggleable(true)
                                .defaultHidden(false)
                                .sorting(0)
                                .build();
                        map.getMarkerSets().put(setId, set);
                    } else if (!label.equals(set.getLabel())) {
                        set.setLabel(label);
                    }
                    ownedSetIds.add(setId);
                    Map<String, MapMarker> previous = appliedByMap.get(mapId);
                    if (previous == null) {
                        // First write to this map in this run: a marker set left behind by a crash may
                        // still hold markers for warps or portals that no longer exist.
                        pruneUnmanaged(set, next);
                        previous = Map.of();
                    }
                    MarkerDiff diff = MarkerDiff.diff(previous, next);
                    for (String removed : diff.removed()) {
                        set.remove(removed);
                    }
                    for (MapMarker changed : diff.changed()) {
                        set.put(changed.id(), toBlueMap(changed));
                    }
                    for (MapMarker added : diff.added()) {
                        set.put(added.id(), toBlueMap(added));
                    }
                    appliedByMap.put(mapId, new LinkedHashMap<>(next));
                    visitedMaps.add(mapId);
                    placed += next.size();
                }
            }
            removeMarkersFromUnvisitedMaps(current, setId, visitedMaps);
            markerCount = placed;
            state = State.ACTIVE;
        } catch (RuntimeException e) {
            state = State.ERROR;
            throw e;
        }
    }

    /** Drops markers stored under our set id that this refresh does not know about. */
    private void pruneUnmanaged(MarkerSet set, Map<String, MapMarker> next) {
        for (String existing : new ArrayList<>(set.getMarkers().keySet())) {
            if (!next.containsKey(existing)) {
                set.remove(existing);
            }
        }
    }

    /**
     * A world that was unloaded, or a map disabled in BlueMap's config, must not keep markers from
     * the previous refresh in this run.
     */
    private void removeMarkersFromUnvisitedMaps(BlueMapAPI current, String setId, Set<String> visitedMaps) {
        for (String mapId : new ArrayList<>(appliedByMap.keySet())) {
            if (visitedMaps.contains(mapId)) {
                continue;
            }
            Map<String, MapMarker> stale = appliedByMap.remove(mapId);
            BlueMapMap map = current.getMap(mapId).orElse(null);
            if (map == null) {
                continue;
            }
            MarkerSet set = map.getMarkerSets().get(setId);
            if (set == null) {
                continue;
            }
            for (String markerId : stale.keySet()) {
                set.remove(markerId);
            }
        }
    }

    private BlueMapWorld resolveWorld(BlueMapAPI current, World bukkitWorld, String worldName) {
        try {
            Optional<BlueMapWorld> direct = current.getWorld(bukkitWorld);
            if (direct.isPresent()) {
                return direct.get();
            }
        } catch (RuntimeException e) {
            // BlueMap's own world adapter is the fast path; scanning by id below is always correct.
            ctx.logger().debug("BlueMap could not map the world object for {}", worldName, e);
        }
        for (BlueMapWorld world : current.getWorlds()) {
            if (world.getId().equalsIgnoreCase(worldName)) {
                return world;
            }
        }
        return null;
    }

    private Marker toBlueMap(MapMarker marker) {
        String label = MapText.escapeHtml(marker.label());
        Vector3d position = new Vector3d(marker.x(), marker.y(), marker.z());
        if (!marker.isArea()) {
            return POIMarker.builder()
                    .label(label)
                    .position(position)
                    .defaultIcon()
                    .build();
        }
        MarkerGeometry.Rect rect = MarkerGeometry.square(marker.x(), marker.z(), marker.areaRadius());
        Shape shape = Shape.createRect(rect.minX(), rect.minZ(), rect.maxX(), rect.maxZ());
        if (marker.category() == MapMarker.Category.SPAWN) {
            return ShapeMarker.builder()
                    .label(label)
                    .detail(label)
                    .position(position)
                    .shape(shape, (float) marker.y())
                    .lineWidth(2)
                    .lineColor(LINE_COLOR)
                    .fillColor(FILL_COLOR)
                    .build();
        }
        MarkerGeometry.YRange range = MarkerGeometry.portalRange(marker.y());
        return ExtrudeMarker.builder()
                .label(label)
                .detail(label)
                .position(position)
                .shape(shape, range.min(), range.max())
                .lineWidth(2)
                .lineColor(LINE_COLOR)
                .fillColor(FILL_COLOR)
                .build();
    }

    @Override
    public int markerCount() {
        return markerCount;
    }

    @Override
    public boolean supportsPlayerVisibility() {
        return true;
    }

    @Override
    public void hidePlayer(UUID uuid, String name) {
        BlueMapAPI current = resolve();
        if (current != null) {
            current.getWebApp().setPlayerVisibility(uuid, false);
        }
    }

    @Override
    public void showPlayer(UUID uuid, String name) {
        BlueMapAPI current = resolve();
        if (current != null) {
            current.getWebApp().setPlayerVisibility(uuid, true);
        }
    }

    @Override
    public void shutdown() {
        BlueMapAPI current = api;
        if (current == null) {
            current = BlueMapAPI.getInstance().orElse(null);
        }
        if (current != null) {
            try {
                removeOwnedSets(current);
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot remove the BlueMap marker sets", e);
            }
        }
        appliedByMap.clear();
        ownedSetIds.clear();
        api = null;
        activeSetId = null;
        markerCount = 0;
        state = State.PENDING;
        BlueMapAPI.unregisterListener(enableListener);
        BlueMapAPI.unregisterListener(disableListener);
    }

    /**
     * Removes every marker set this hook created, plus the currently configured id (which covers a
     * config change between two runs). Marker sets from other plugins are never touched.
     */
    private void removeOwnedSets(BlueMapAPI current) {
        Set<String> ids = new LinkedHashSet<>(ownedSetIds);
        if (activeSetId != null) {
            ids.add(activeSetId);
        }
        ids.add(MapText.sanitizeId(config.get().bluemapMarkerSetId, DEFAULT_SET_ID));
        for (BlueMapMap map : current.getMaps()) {
            for (String id : ids) {
                map.getMarkerSets().remove(id);
            }
        }
        ownedSetIds.clear();
    }
}
