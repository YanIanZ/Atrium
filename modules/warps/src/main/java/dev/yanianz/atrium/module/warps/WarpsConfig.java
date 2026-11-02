package dev.yanianz.atrium.module.warps;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/warps.yml */
@ConfigSerializable
public class WarpsConfig {
    @Comment("Require a per-warp permission to use a warp: the node set with /editwarp permission, or\n"
            + "atrium.warp.<name> when none was set. Holders of atrium.warps.all bypass it. When false every\n"
            + "player with atrium.warp can use every warp. Hidden warps are also left out of /warps and tab\n"
            + "completion.")
    public boolean requirePerWarpPermission = false;

    @Comment("Category for warps created without one.")
    public String defaultCategory = "general";

    @Comment("Use the warmup and cooldown when a staff member sends another player to a warp.")
    public boolean warmupWhenSendingOthers = false;

    @Comment("Open the paginated chest menu for players from /warps. Console and players when this is false\n"
            + "get the text list instead.")
    public boolean guiEnabled = true;

    @Comment("Rows of the /warps chest menu, 3 to 6 (the bottom row holds the page buttons).")
    public int guiRows = 6;

    @Comment("Icon of a warp without an entry under 'icons' (Bukkit material name, e.g. CHEST or COMPASS).")
    public String defaultIcon = "CHEST";

    @Comment("Menu icons set with /setwarpicon. Only the material name and the optional custom model data are\n"
            + "stored, never the full item. Keys are warp names (case-insensitive).")
    public Map<String, Icon> icons = new LinkedHashMap<>();

    @Comment("How many warps a player may own when they have atrium.warps.create. Staff with atrium.setwarp\n"
            + "are not limited. Per-player overrides: atrium.warps.limit.<n>, and atrium.warps.limit.unlimited\n"
            + "for no limit.")
    public int defaultPlayerWarpLimit = 2;

    /** The configured icon of {@code warpKey}, or null to use {@link #defaultIcon}. */
    public Icon icon(String warpKey) {
        if (icons == null) {
            return null;
        }
        Icon exact = icons.get(warpKey);
        if (exact != null) {
            return exact;
        }
        String wanted = warpKey.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Icon> entry : icons.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** One menu icon: a material plus an optional custom model data (negative = none). */
    @ConfigSerializable
    public static class Icon {
        @Comment("Bukkit material name of the menu icon.")
        public String material = "CHEST";

        @Comment("Custom model data of the menu icon, -1 for none.")
        public int customModelData = -1;

        public Icon() {}

        public Icon(String material, int customModelData) {
            this.material = material;
            this.customModelData = customModelData;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Icon icon
                    && customModelData == icon.customModelData
                    && java.util.Objects.equals(material, icon.material);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(material, customModelData);
        }
    }
}
