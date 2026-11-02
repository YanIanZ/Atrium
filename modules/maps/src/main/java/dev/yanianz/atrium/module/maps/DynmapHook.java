package dev.yanianz.atrium.module.maps;

import dev.yanianz.atrium.core.config.ConfigHandle;
import dev.yanianz.atrium.core.module.ModuleContext;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.plugin.Plugin;
import org.dynmap.DynmapCommonAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.Marker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerIcon;
import org.dynmap.markers.MarkerSet;

/**
 * dynmap integration.
 *
 * <p>dynmap's API is synchronised internally, but marker and visibility calls are still made from the
 * global region thread. Vanished players are hidden through dynmap's own per-plugin visibility
 * assertion, so two plugins hiding the same player do not fight over one boolean.
 */
final class DynmapHook implements MapHook {
    private static final int LINE_COLOR = 0x55FF55;
    private static final int FILL_COLOR = 0x55FF55;

    private final ModuleContext ctx;
    private final ConfigHandle<MapsConfig> config;
    private final DynmapCommonAPI api;
    private final String pluginId;
    private MarkerSet markerSet;
    private String activeSetId;
    private Map<String, MapMarker> applied = new LinkedHashMap<>();
    private volatile State state = State.PENDING;
    private volatile int markerCount;
    private boolean warnedMissingIcon;

    DynmapHook(ModuleContext ctx, ConfigHandle<MapsConfig> config) {
        this.ctx = ctx;
        this.config = config;
        Plugin plugin = ctx.plugin().getServer().getPluginManager().getPlugin("dynmap");
        if (!(plugin instanceof DynmapCommonAPI common)) {
            throw new IllegalStateException("dynmap is enabled but does not expose DynmapCommonAPI");
        }
        this.api = common;
        this.pluginId = ctx.plugin().getName();
    }

    @Override
    public String id() {
        return "dynmap";
    }

    @Override
    public String displayName() {
        return "dynmap";
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public void apply(Collection<MapMarker> markers) {
        MarkerAPI markerApi = api.getMarkerAPI();
        if (markerApi == null || !api.markerAPIInitialized()) {
            state = State.PENDING;
            markerCount = 0;
            return;
        }
        MapsConfig cfg = config.get();
        String setId = cfg.markerSetId;
        String label = MapText.escapeHtml(cfg.markerSetLabel);
        if (markerSet != null && activeSetId != null && !activeSetId.equals(setId)) {
            // The marker set id changed on reload: remove the old set instead of leaving a ghost layer.
            markerSet.deleteMarkerSet();
            markerSet = null;
            applied = new LinkedHashMap<>();
        }
        activeSetId = setId;
        MarkerSet set = markerSet;
        if (set == null || markerApi.getMarkerSet(setId) == null) {
            set = markerApi.getMarkerSet(setId);
            if (set == null) {
                set = markerApi.createMarkerSet(setId, label, null, false);
            }
            if (set == null) {
                state = State.ERROR;
                throw new IllegalStateException("dynmap refused marker set '" + setId + "'");
            }
            set.setHideByDefault(false);
            set.setLayerPriority(0);
            markerSet = set;
        }
        if (!label.equals(set.getMarkerSetLabel())) {
            set.setMarkerSetLabel(label);
        }

        Map<String, MapMarker> next = MarkerDiff.index(markers);
        MarkerDiff diff = MarkerDiff.diff(applied, next);
        boolean failed = false;
        Map<String, MapMarker> placed = new LinkedHashMap<>();
        for (Map.Entry<String, MapMarker> entry : next.entrySet()) {
            MapMarker marker = entry.getValue();
            boolean unchanged = applied.containsKey(entry.getKey()) && applied.get(entry.getKey()).equals(marker);
            if (unchanged) {
                placed.put(entry.getKey(), marker);
                continue;
            }
            if (applied.containsKey(entry.getKey())) {
                delete(set, entry.getKey());
            }
            if (create(markerApi, set, marker)) {
                placed.put(entry.getKey(), marker);
            } else {
                failed = true;
            }
        }
        for (String removed : diff.removed()) {
            delete(set, removed);
        }
        applied = placed;
        markerCount = placed.size();
        state = failed ? State.ERROR : State.ACTIVE;
    }

    private boolean create(MarkerAPI markerApi, MarkerSet set, MapMarker marker) {
        String label = MapText.escapeHtml(marker.label());
        try {
            if (marker.isArea()) {
                double r = marker.areaRadius();
                double[] xs = {marker.x() - r, marker.x() + r, marker.x() + r, marker.x() - r};
                double[] zs = {marker.z() - r, marker.z() - r, marker.z() + r, marker.z() + r};
                AreaMarker area = set.createAreaMarker(marker.id(), label, false, marker.world(), xs, zs, false);
                if (area == null) {
                    ctx.logger().warn("dynmap refused area marker {} in world {}", marker.id(), marker.world());
                    return false;
                }
                area.setRangeY(marker.y() - 1, marker.y() + 2);
                area.setLineStyle(2, 0.85, LINE_COLOR);
                area.setFillStyle(0.35, FILL_COLOR);
                area.setDescription(label);
                return true;
            }
            MarkerIcon icon = icon(markerApi);
            Marker created = set.createMarker(marker.id(), label, marker.world(), marker.x(), marker.y(),
                    marker.z(), icon, false);
            if (created == null) {
                ctx.logger().warn("dynmap refused marker {} in world {}", marker.id(), marker.world());
                return false;
            }
            created.setDescription(label);
            return true;
        } catch (RuntimeException e) {
            ctx.logger().warn("dynmap rejected marker {} in world {}", marker.id(), marker.world(), e);
            return false;
        }
    }

    private MarkerIcon icon(MarkerAPI markerApi) {
        String iconId = config.get().dynmapIcon;
        if (iconId == null || iconId.isBlank()) {
            return null;
        }
        MarkerIcon icon = markerApi.getMarkerIcon(iconId);
        if (icon == null && !warnedMissingIcon) {
            warnedMissingIcon = true;
            ctx.logger().warn("Unknown dynmap marker icon '{}'; using the default icon", iconId);
        }
        return icon;
    }

    private void delete(MarkerSet set, String id) {
        Marker marker = set.findMarker(id);
        if (marker != null) {
            marker.deleteMarker();
        }
        AreaMarker area = set.findAreaMarker(id);
        if (area != null) {
            area.deleteMarker();
        }
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
        api.assertPlayerInvisibility(name, true, pluginId);
    }

    @Override
    public void showPlayer(UUID uuid, String name) {
        // assertPlayerVisibility(name, true) asserts that a player hidden by dynmap's configuration should
        // be shown; it does not clear the per-plugin invisibility entry left by hidePlayer, so the player
        // stays hidden. Clear the assertion itself instead.
        api.assertPlayerInvisibility(name, false, pluginId);
    }

    @Override
    public void shutdown() {
        MarkerSet set = markerSet;
        if (set == null) {
            MarkerAPI markerApi = api.getMarkerAPI();
            if (markerApi != null) {
                set = markerApi.getMarkerSet(config.get().markerSetId);
            }
        }
        if (set != null) {
            set.deleteMarkerSet();
        }
        markerSet = null;
        activeSetId = null;
        applied = new LinkedHashMap<>();
        markerCount = 0;
        state = State.PENDING;
    }
}
