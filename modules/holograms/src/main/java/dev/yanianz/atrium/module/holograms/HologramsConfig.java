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
