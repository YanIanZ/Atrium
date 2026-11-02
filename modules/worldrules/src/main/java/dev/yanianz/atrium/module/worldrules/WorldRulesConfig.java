package dev.yanianz.atrium.module.worldrules;

import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.World;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/** plugins/Atrium/modules/worldrules.yml */
@ConfigSerializable
public class WorldRulesConfig {
    @Comment("Per-world rules, keyed by world name. A \"*\" entry is the fallback for worlds without an entry.\n"
            + "The generated \"*\" section documents every option; edit it or add specific worlds.")
    public Map<String, WorldRule> worlds = defaultWorlds();

    private static Map<String, WorldRule> defaultWorlds() {
        Map<String, WorldRule> map = new LinkedHashMap<>();
        map.put("*", new WorldRule());
        return map;
    }

    @Comment("Armor sets that grant infinite potion effects while enough configured pieces are worn,\n"
            + "keyed by set name. The first complete set in this map wins.")
    public Map<String, ArmorSet> armorSets = new LinkedHashMap<>();

    @Comment("Ticks between compass updates while tracking another player (5-200).")
    public int compassRefreshTicks = 20;

    @Comment("Maximum number of players tracking another player with /compass at once (1-1000).")
    public int maxCompassTrackers = 200;

    @Comment("How far /blockcycle can reach, in blocks (1-50).")
    public int blockCycleReach = 6;

    @Comment("Also fire Bukkit's BlockPlaceEvent before cycling, so claim/region plugins can deny the change.\n"
            + "Atrium's own BlockCycleEvent is always fired first.")
    public boolean blockCycleProtectionCheck = true;

    WorldRule rule(String worldName) {
        return WorldRules.resolve(worlds, worldName);
    }

    WorldRule rule(World world) {
        return world == null ? WorldRule.NONE : rule(world.getName());
    }

    int compassRefreshTicks() {
        return clamp(compassRefreshTicks, 5, 200);
    }

    int maxCompassTrackers() {
        return clamp(maxCompassTrackers, 1, 1000);
    }

    int blockCycleReach() {
        return clamp(blockCycleReach, 1, 50);
    }

    /** Reports configuration mistakes at load time instead of failing silently at runtime. */
    void validate(java.util.function.Consumer<String> warn) {
        if (worlds == null) {
            return;
        }
        for (Map.Entry<String, WorldRule> entry : worlds.entrySet()) {
            String name = entry.getKey();
            WorldRule rule = entry.getValue();
            if (name == null || name.isBlank()) {
                warn.accept("worlds: blank world name");
                continue;
            }
            if (rule == null) {
                warn.accept("worlds." + name + ": empty rule");
                continue;
            }
            if (rule.forceGameMode != null && !rule.forceGameMode.isBlank() && rule.forcedGameMode() == null) {
                warn.accept("worlds." + name + ": unknown force-game-mode '" + rule.forceGameMode + "'");
            }
            if (rule.gatewayMode() == WorldRule.GatewayMode.FIXED
                    && WorldRules.parseGatewayTarget(rule.gatewayTarget) == null) {
                warn.accept("worlds." + name + ": FIXED end gateway needs a valid gateway-target");
            }
            if (rule.endGateway != null && !rule.endGateway.isBlank()
                    && rule.gatewayMode() == WorldRule.GatewayMode.NORMAL
                    && !rule.endGateway.trim().equalsIgnoreCase("NORMAL")) {
                warn.accept("worlds." + name + ": unknown end-gateway '" + rule.endGateway + "'");
            }
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
