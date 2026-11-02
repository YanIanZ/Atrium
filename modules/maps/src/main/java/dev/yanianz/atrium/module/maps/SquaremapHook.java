package dev.yanianz.atrium.module.maps;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import xyz.jpenilla.squaremap.api.BukkitAdapter;
import xyz.jpenilla.squaremap.api.Key;
import xyz.jpenilla.squaremap.api.MapWorld;
import xyz.jpenilla.squaremap.api.Point;
import xyz.jpenilla.squaremap.api.SimpleLayerProvider;
import xyz.jpenilla.squaremap.api.Squaremap;
import xyz.jpenilla.squaremap.api.SquaremapProvider;
import xyz.jpenilla.squaremap.api.WorldIdentifier;
import xyz.jpenilla.squaremap.api.marker.Marker;
import xyz.jpenilla.squaremap.api.marker.MarkerOptions;

/**
 * squaremap integration: one layer per world, markers diffed instead of cleared and re-added, and
 * {@code PlayerManager} for vanish hiding.
 *
 * <p>The squaremap API is not documented as thread-safe, so every call runs on the global region
 * thread. The provider is resolved lazily because squaremap may finish enabling after Atrium.
 */
final class SquaremapHook implements MapHook {
    private static final Color AREA_LINE = new Color(85, 255, 85);
    private static final Color AREA_FILL = new Color(85, 255, 85);

    private final ModuleContext ctx;
    private final ConfigHandle<MapsConfig> config;
    private final Map<String, SimpleLayerProvider> layers = new HashMap<>();
    private final Map<String, Map<String, MapMarker>> appliedByWorld = new HashMap<>();
    private volatile Squaremap api;
    private volatile State state = State.PENDING;
    private volatile int markerCount;
    private String activeSetId;
    private String activeLabel;

    SquaremapHook(ModuleContext ctx, ConfigHandle<MapsConfig> config) {
        this.ctx = ctx;
        this.config = config;
    }

    @Override
    public String id() {
        return "squaremap";
    }

    @Override
    public String displayName() {
        return "squaremap";
    }

    @Override
    public State state() {
        return state;
    }

    private Squaremap resolve() {
        Squaremap current = api;
        if (current != null) {
            return current;
        }
        try {
            current = SquaremapProvider.get();
        } catch (RuntimeException e) {
            // The provider is registered when squaremap finishes enabling. Retry on the next refresh.
            ctx.logger().debug("squaremap provider is not ready yet", e);
            return null;
        }
        api = current;
        return current;
    }

    @Override
    public void apply(Collection<MapMarker> markers) {
        Squaremap squaremap = resolve();
        if (squaremap == null) {
            state = State.PENDING;
            markerCount = 0;
            return;
        }
        MapsConfig cfg = config.get();
        String setId = cfg.markerSetId;
        String label = MapText.escapeHtml(cfg.markerSetLabel);
        if (activeSetId != null && (!activeSetId.equals(setId) || !activeLabel.equals(label))) {
            // Layers are immutable in squaremap, so a changed id or label needs a fresh layer.
            unregisterAll(squaremap, Key.key(activeSetId));
            layers.clear();
            appliedByWorld.clear();
        }
        activeSetId = setId;
        activeLabel = label;
        Key layerKey = Key.key(setId);
        Map<String, List<MapMarker>> byWorld = new HashMap<>();
        for (MapMarker marker : markers) {
            byWorld.computeIfAbsent(marker.world(), key -> new ArrayList<>()).add(marker);
        }
        try {
            int placed = 0;
            Map<String, Boolean> activeWorlds = new LinkedHashMap<>();
            for (Map.Entry<String, List<MapMarker>> entry : byWorld.entrySet()) {
                World world = Bukkit.getWorld(entry.getKey());
                if (world == null) {
                    continue;
                }
                WorldIdentifier identifier = BukkitAdapter.worldIdentifier(world);
                MapWorld mapWorld = squaremap.getWorldIfEnabled(identifier).orElse(null);
                if (mapWorld == null) {
                    continue;
                }
                SimpleLayerProvider layer = layers.get(entry.getKey());
                if (layer == null) {
                    layer = SimpleLayerProvider.builder(label)
                            .showControls(true)
                            .defaultHidden(false)
                            .layerPriority(0)
                            .build();
                    layers.put(entry.getKey(), layer);
                }
                if (!mapWorld.layerRegistry().hasEntry(layerKey)) {
                    mapWorld.layerRegistry().register(layerKey, layer);
                }
                Map<String, MapMarker> next = MarkerDiff.index(entry.getValue());
                MarkerDiff diff = MarkerDiff.diff(appliedByWorld.getOrDefault(entry.getKey(), Map.of()), next);
                for (String removed : diff.removed()) {
                    layer.removeMarker(Key.key(removed));
                }
                for (MapMarker changed : diff.changed()) {
                    layer.addMarker(Key.key(changed.id()), toSquaremap(changed));
                }
                for (MapMarker added : diff.added()) {
                    layer.addMarker(Key.key(added.id()), toSquaremap(added));
                }
                appliedByWorld.put(entry.getKey(), new LinkedHashMap<>(next));
                activeWorlds.put(entry.getKey(), Boolean.TRUE);
                placed += next.size();
            }
            removeStaleLayers(squaremap, layerKey, activeWorlds);
            markerCount = placed;
            state = State.ACTIVE;
        } catch (RuntimeException e) {
            state = State.ERROR;
            throw e;
        }
    }

    private void removeStaleLayers(Squaremap squaremap, Key layerKey, Map<String, Boolean> activeWorlds) {
        for (String worldName : new ArrayList<>(layers.keySet())) {
            if (activeWorlds.containsKey(worldName)) {
                continue;
            }
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                WorldIdentifier identifier = BukkitAdapter.worldIdentifier(world);
                squaremap.getWorldIfEnabled(identifier)
                        .ifPresent(mapWorld -> mapWorld.layerRegistry().unregister(layerKey));
            }
            layers.remove(worldName);
            appliedByWorld.remove(worldName);
        }
    }

    /** Unregisters the given layer id from every world we know about. */
    private void unregisterAll(Squaremap squaremap, Key layerKey) {
        for (String worldName : new ArrayList<>(layers.keySet())) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                WorldIdentifier identifier = BukkitAdapter.worldIdentifier(world);
                squaremap.getWorldIfEnabled(identifier)
                        .ifPresent(mapWorld -> mapWorld.layerRegistry().unregister(layerKey));
            }
        }
    }

    private Marker toSquaremap(MapMarker marker) {
        String label = MapText.escapeHtml(marker.label());
        MarkerOptions options = MarkerOptions.builder()
                .stroke(true)
                .strokeColor(AREA_LINE)
                .strokeWeight(2)
                .strokeOpacity(0.85)
                .fill(true)
                .fillColor(AREA_FILL)
                .fillOpacity(0.35)
                .clickTooltip(label)
                .hoverTooltip(label)
                .build();
        Marker markerShape;
        if (marker.isArea()) {
            double r = marker.areaRadius();
            markerShape = Marker.rectangle(
                    Point.of(marker.x() - r, marker.z() - r),
                    Point.of(marker.x() + r, marker.z() + r));
        } else {
            markerShape = Marker.circle(Point.of(marker.x(), marker.z()), config.get().pointMarkerRadius);
        }
        return markerShape.markerOptions(options);
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
        Squaremap squaremap = resolve();
        if (squaremap != null) {
            squaremap.playerManager().hide(uuid);
        }
    }

    @Override
    public void showPlayer(UUID uuid, String name) {
        Squaremap squaremap = resolve();
        if (squaremap != null) {
            squaremap.playerManager().show(uuid);
        }
    }

    @Override
    public void shutdown() {
        Squaremap squaremap = api;
        if (squaremap != null) {
            try {
                String id = activeSetId != null ? activeSetId : config.get().markerSetId;
                unregisterAll(squaremap, Key.key(id));
            } catch (RuntimeException e) {
                ctx.logger().warn("Cannot remove the squaremap layers", e);
            }
        }
        layers.clear();
        appliedByWorld.clear();
        api = null;
        activeSetId = null;
        activeLabel = null;
        markerCount = 0;
        state = State.PENDING;
    }
}
