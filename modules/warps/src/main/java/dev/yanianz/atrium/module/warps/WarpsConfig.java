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

    @Comment("Teleport cost of a warp that has no entry under 'costs', in server currency. 0 is free.\n"
            + "The teleporting player pays; a failed teleport is refunded. Holders of atrium.warps.free never pay.")
    public double defaultCost = 0;

    @Comment("Per-warp teleport cost, overriding default-cost. Keys are warp names (case-insensitive).\n"
            + "A value of 0 makes that warp free.")
    public Map<String, Double> costs = new LinkedHashMap<>();

    @Comment("Cooldown of a warp that has no entry under 'cooldowns', in seconds. 0 disables the cooldown.\n"
            + "Cooldowns are per player and persist across restarts; atrium.warps.free bypasses them.")
    public long defaultCooldownSeconds = 0;

    @Comment("Per-warp cooldown in seconds, overriding default-cooldown-seconds (case-insensitive warp names).")
    public Map<String, Long> cooldowns = new LinkedHashMap<>();

    @Comment("Charge the player who is sent to a warp when a staff member uses /warp <name> <player>.\n"
            + "When false, sending another player is free for them (the sender is never charged).")
    public boolean chargeWhenSendingOthers = false;

    @Comment("How the random-location deck of a warp behaves when extra locations were added with\n"
            + "/editwarp <warp> addlocation: NON_REPEATING hands out every location once before shuffling\n"
            + "again, REPEATING picks a random location every time (a location can repeat twice in a row).")
    public String randomMode = "NON_REPEATING";

    /** Why {@link #randomMode} is invalid, or {@code null} when it is usable. */
    public String randomModeProblem() {
        String mode = randomMode == null ? "" : randomMode.strip().toUpperCase(Locale.ROOT);
        return mode.equals("NON_REPEATING") || mode.equals("REPEATING") ? null
                : "random-mode must be NON_REPEATING or REPEATING";
    }

    public boolean repeatingRandomLocations() {
        return randomMode != null && randomMode.strip().equalsIgnoreCase("REPEATING");
    }

    /**
     * The configured cost of {@code warpKey}, or {@link #defaultCost} when none was set. An explicit entry of
     * 0 makes the warp free even when a default cost is configured.
     */
    public double cost(String warpKey) {
        Double exact = lookup(costs, warpKey);
        if (exact != null) {
            return Double.isFinite(exact) && exact > 0 ? exact : 0;
        }
        return Math.max(0, defaultCost);
    }

    /** The cooldown of {@code warpKey} in seconds; an explicit entry of 0 disables it. */
    public long cooldownSeconds(String warpKey) {
        Long exact = lookup(cooldowns, warpKey);
        return exact != null ? Math.max(0, exact) : Math.max(0, defaultCooldownSeconds);
    }

    /** Case-insensitive lookup of one map entry; a blank/absent key returns {@code null}. */
    static <T> T lookup(Map<String, T> map, String key) {
        if (map == null || key == null) {
            return null;
        }
        T exact = map.get(key);
        if (exact != null) {
            return exact;
        }
        String wanted = key.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, T> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return null;
    }

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
