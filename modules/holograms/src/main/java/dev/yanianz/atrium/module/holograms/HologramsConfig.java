package dev.yanianz.atrium.module.holograms;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/holograms.yml */
@ConfigSerializable
public class HologramsConfig {
    @Comment("Hologram engine: auto, native, decentholograms or fancyholograms.\n"
            + "auto = DecentHolograms when enabled, else FancyHolograms when enabled, else native.\n"
            + "Atrium always owns the data; external engines receive mirrors named with the 'atrium_' prefix.\n"
            + "Per-viewer placeholders, click actions, block lines and line offsets are native-only; item lines\n"
            + "also work on DecentHolograms. The editor reports unsupported features per engine.\n"
            + "DecentHolograms needs 2.10+ on Folia.")
    public String engine = "auto";

    @Comment("Settings copied into every NEW hologram. Change an existing hologram with\n"
            + "/holo setting <name> <option> <value>.")
    public Defaults defaults = new Defaults();

    @Comment("Size estimates used for the stacking of lines and for the click box. The server cannot measure\n"
            + "glyphs, so text width = visible characters x char-width (wrapped at the line width).")
    public Bounds bounds = new Bounds();

    @Comment("Ticks between the per-player checks that show/hide holograms by range and permission and\n"
            + "refresh per-viewer text. Runs on each player's own thread.")
    public int viewerCheckTicks = 10;

    @Comment("Ticks between the per-hologram timer runs (page timer, text refresh, self-repair). Intervals\n"
            + "shorter than this are rounded up to it.")
    public int hologramTickPeriod = 5;

    @Comment("Per-viewer holograms (placeholders or per-viewer: ON) spawn one set of display entities for each\n"
            + "viewer in range. At most this many viewers per hologram get their own copy; anyone beyond the cap sees\n"
            + "the shared copy rendered without a player (placeholders resolved for nobody). Keeps entity counts bounded.")
    public int maxPersonalViewers = 30;

    @Comment("Limits for editing.")
    public int maxLinesPerPage = 40;

    @Comment("Maximum pages per hologram.")
    public int maxPages = 20;

    @Comment("Maximum characters of one text line.")
    public int maxLineLength = 1024;

    @Comment("Maximum size in bytes of an item saved with #hand.")
    public int maxSavedItemBytes = 24_576;

    @Comment("Default radius of /holo near.")
    public double nearRadius = 32.0;

    @Comment("Holograms per page of /holo list.")
    public int listPageSize = 10;

    @Comment("Require atrium.holograms.see.<name> (lower-case name) in addition to the permission setting of\n"
            + "each hologram. Off by default: an existing server keeps its current visibility.")
    public boolean perHologramPermission = false;

    @Comment("Character used to expand %filler% in a line (the page width comes from its longest line).")
    public String filler = " ";

    @Comment("Materials for icon lines that name a custom model data value (#cmd:<n>).")
    public Icons icons = new Icons();

    @ConfigSerializable
    public static class Icons {
        @Comment("From custom model data to a material key, for example \"12345: diamond_sword\".\n"
                + "Values are the material shown; the custom model data itself stays on the item in the resource pack.")
        public java.util.List<String> customModelData = new java.util.ArrayList<>(java.util.List.of(
                "10001: paper", "10002: player_head"));
    }

    @Comment("Player skin images for #img:head/bust/body lines. Native engine only.")
    public Images images = new Images();

    @ConfigSerializable
    public static class Images {
        @Comment("Download the skin from the texture URL in the player's Mojang profile. Off = every image line\n"
                + "uses the built-in default skin. The URL must be https://textures.minecraft.net/...; no other host\n"
                + "is contacted. Offline-mode servers always use the default skin.")
        public boolean fetchSkins = true;

        @Comment("Largest accepted skin PNG in bytes (1 KiB-1 MiB).")
        public int maxBytes = 65_536;

        @Comment("Connect and read timeout in seconds (1-60).")
        public int timeoutSeconds = 8;

        @Comment("Hours a downloaded skin stays in the memory and disk cache (1-8760).")
        public int cacheHours = 168;
    }

    String fillerChar() {
        return filler == null || filler.isEmpty() ? " " : filler.substring(0, 1);
    }

    /** Material for a {@code #cmd:<n>} icon line; null when the value is not configured. */
    org.bukkit.Material iconMaterial(int customModelData) {
        String key = Integer.toString(customModelData);
        for (String entry : icons.customModelData) {
            if (entry == null) {
                continue;
            }
            int colon = entry.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            if (entry.substring(0, colon).strip().equals(key)) {
                return org.bukkit.Material.matchMaterial(entry.substring(colon + 1).strip());
            }
        }
        return null;
    }

    @ConfigSerializable
    public static class Defaults {
        @Comment("How lines turn to the viewer: CENTER, VERTICAL, HORIZONTAL or FIXED (uses the creator's yaw).")
        public String billboard = "CENTER";

        @Comment("Size multiplier of the whole hologram.")
        public float scale = 1.0f;

        @Comment("Text background: default, none, #RRGGBB or #AARRGGBB.")
        public String background = "default";

        @Comment("Text shadow.")
        public boolean shadow = false;

        @Comment("Text visible through blocks.")
        public boolean seeThrough = false;

        @Comment("Gap in blocks between stacked elements (text blocks, items, blocks). Lines inside one text\n"
                + "block use the font's own spacing.")
        public float lineSpacing = 0.02f;

        @Comment("Text alignment: CENTER, LEFT or RIGHT.")
        public String alignment = "CENTER";

        @Comment("View range in blocks.")
        public float viewRange = 48f;

        @Comment("Text wrap width in text pixels (vanilla 200).")
        public int lineWidth = 200;

        @Comment("Ticks between refreshes of text with placeholders (0 = never).")
        public int updateInterval = 40;

        @Comment("Ticks between automatic page switches (0 = off).")
        public int pageInterval = 0;

        @Comment("Milliseconds between two clicks of one player on the same hologram.")
        public int clickCooldown = 500;

        @Comment("Without click actions, right click = next page and left click = previous page.")
        public boolean clickPages = true;

        @Comment("Per-viewer copies: AUTO (when the text has placeholders), ON or OFF.")
        public String perViewer = "AUTO";

        @Comment("Show the hologram. Off parks it (no entities, no clicks) but keeps every line and setting.")
        public boolean enabled = true;

        @Comment("Degrees per second around the vertical axis for new holograms (0 = still). Only a FIXED or\n"
                + "HORIZONTAL billboard visibly turns; the others face the viewer.")
        public float rotateSpeed = 0f;

        @Comment("Ticks a new page needs to grow to full size after a page change (0 = instant).")
        public int fadeTicks = 0;
    }

    @ConfigSerializable
    public static class Bounds {
        @Comment("Blocks per visible character at scale 1 (average glyph 6 text pixels = 0.15).")
        public double charWidth = 0.15;

        @Comment("Blocks per text line at scale 1 (10 text pixels = 0.25).")
        public double lineHeight = 0.25;

        @Comment("Edge length in blocks of an item line at scale 1.")
        public double itemSize = 0.5;

        @Comment("Edge length in blocks of a block line at scale 1.")
        public double blockSize = 0.5;

        @Comment("Extra space in blocks around the content on every side of the click box.")
        public double interactionPadding = 0.1;
    }

    /** The defaults as a settings value. Invalid entries keep the built-in default and are reported. */
    HologramSettings defaultSettings(java.util.function.BiConsumer<String, String> problems) {
        HologramSettings settings = HologramSettings.builtInDefaults();
        Object[][] entries = {
            {Setting.BILLBOARD, defaults.billboard}, {Setting.SCALE, Float.toString(defaults.scale)},
            {Setting.BACKGROUND, defaults.background}, {Setting.SHADOW, Boolean.toString(defaults.shadow)},
            {Setting.SEE_THROUGH, Boolean.toString(defaults.seeThrough)},
            {Setting.LINE_SPACING, Float.toString(defaults.lineSpacing)}, {Setting.ALIGNMENT, defaults.alignment},
            {Setting.VIEW_RANGE, Float.toString(defaults.viewRange)}, {Setting.LINE_WIDTH, Integer.toString(defaults.lineWidth)},
            {Setting.UPDATE_INTERVAL, Integer.toString(defaults.updateInterval)},
            {Setting.PAGE_INTERVAL, Integer.toString(defaults.pageInterval)},
            {Setting.CLICK_COOLDOWN, Integer.toString(defaults.clickCooldown)},
            {Setting.CLICK_PAGES, Boolean.toString(defaults.clickPages)}, {Setting.PER_VIEWER, defaults.perViewer},
            {Setting.ENABLED, Boolean.toString(defaults.enabled)},
            {Setting.ROTATE_SPEED, Float.toString(defaults.rotateSpeed)},
            {Setting.FADE_TICKS, Integer.toString(defaults.fadeTicks)},
        };
        for (Object[] entry : entries) {
            Setting setting = (Setting) entry[0];
            try {
                settings = settings.with(setting, String.valueOf(entry[1]));
            } catch (IllegalArgumentException e) {
                problems.accept(setting.key, e.getMessage());
            }
        }
        return settings;
    }

    Layout.Metrics metrics() {
        return new Layout.Metrics(Math.max(0.001, bounds.charWidth), Math.max(0.001, bounds.lineHeight),
                Math.max(0.01, bounds.itemSize), Math.max(0.01, bounds.blockSize), Math.max(0, bounds.interactionPadding));
    }
}
