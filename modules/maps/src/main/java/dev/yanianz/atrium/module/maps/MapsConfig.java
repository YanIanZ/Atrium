package dev.yanianz.atrium.module.maps;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/maps.yml */
@ConfigSerializable
public class MapsConfig {
    @Comment("Seconds between automatic marker refreshes. Warp and portal changes also trigger a refresh,\n"
            + "so this is only a safety net. 5-86400.")
    public int refreshIntervalSeconds = 300;

    @Comment("Seconds between checks that every vanished player is hidden and every visible player is shown\n"
            + "again. This repairs a map plugin that was reloaded or unavailable during a vanish change.\n"
            + "5-3600.")
    public int visibilityResyncSeconds = 30;

    @Comment("Marker set / layer id used on squaremap and dynmap. Lower-case letters, digits, '-' and '_'\n"
            + "only.")
    public String markerSetId = "atrium";

    @Comment("Marker set id used on BlueMap. BlueMap keeps one set per map, so this may differ from\n"
            + "markerSetId. Lower-case letters, digits, '-' and '_' only.")
    public String bluemapMarkerSetId = "atrium_warps";

    @Comment("Marker set label shown in the map layer list.")
    public String markerSetLabel = "Atrium";

    @Comment("Publish one marker per warp. Warp names come from players, so labels are HTML-escaped.")
    public boolean warpMarkers = true;

    @Comment("Publish a spawn marker.")
    public boolean spawnMarker = true;

    @Comment("Label of the spawn marker.")
    public String spawnMarkerLabel = "Spawn";

    @Comment("Explicit spawn location in the form 'world;x;y;z;yaw;pitch'. Leave empty to use the spawn of\n"
            + "spawnWorld. Example: world;0;64;0;0;0")
    public String spawnLocation = "";

    @Comment("World whose spawn is published when spawnLocation is empty. Empty means the first loaded world.")
    public String spawnWorld = "";

    @Comment("Draw the spawn as a square area marker with this radius in blocks. 0 keeps a point marker.\n"
            + "0-64.")
    public int spawnAreaRadius = 0;

    @Comment("Publish area markers for portals that players have used since the module started. The portals\n"
            + "module exposes use events, not its region registry, so a portal appears after its first use.")
    public boolean portalMarkers = true;

    @Comment("Square radius in blocks of a portal area marker. 1-64.")
    public int portalMarkerRadius = 4;

    @Comment("Maximum number of remembered portal regions. Older entries stop being updated when full. 1-1000.")
    public int portalMarkerMax = 100;

    @Comment("Hide vanished players on map backends that render live player markers (squaremap, dynmap).")
    public boolean hideVanished = true;

    @Comment("dynmap marker icon id for point markers. Leave empty for dynmap's default icon.")
    public String dynmapIcon = "";

    @Comment("Radius in blocks of a point marker on squaremap, which draws circles instead of icons. 1-32.")
    public int pointMarkerRadius = 3;

    /** Clamps every numeric option and normalises the ids so a bad config cannot break a backend. */
    static void validate(MapsConfig cfg) {
        cfg.refreshIntervalSeconds = clamp(cfg.refreshIntervalSeconds, 5, 86400);
        cfg.visibilityResyncSeconds = clamp(cfg.visibilityResyncSeconds, 5, 3600);
        cfg.portalMarkerRadius = clamp(cfg.portalMarkerRadius, 1, 64);
        cfg.portalMarkerMax = clamp(cfg.portalMarkerMax, 1, 1000);
        cfg.spawnAreaRadius = clamp(cfg.spawnAreaRadius, 0, 64);
        cfg.pointMarkerRadius = clamp(cfg.pointMarkerRadius, 1, 32);
        cfg.markerSetId = MapText.sanitizeId(cfg.markerSetId, "atrium");
        cfg.bluemapMarkerSetId = MapText.sanitizeId(cfg.bluemapMarkerSetId, "atrium_warps");
        if (cfg.markerSetLabel == null || cfg.markerSetLabel.isBlank()) {
            cfg.markerSetLabel = "Atrium";
        }
        if (cfg.spawnMarkerLabel == null || cfg.spawnMarkerLabel.isBlank()) {
            cfg.spawnMarkerLabel = "Spawn";
        }
        if (cfg.spawnLocation == null) {
            cfg.spawnLocation = "";
        }
        if (cfg.spawnWorld == null) {
            cfg.spawnWorld = "";
        }
        if (cfg.dynmapIcon == null) {
            cfg.dynmapIcon = "";
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
