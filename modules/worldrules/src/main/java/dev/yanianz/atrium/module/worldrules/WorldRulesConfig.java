package dev.yanianz.atrium.module.worldrules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

    @Comment("Death handling: keep-inventory and keep-experience permissions, automatic respawn.")
    public Death death = new Death();

    @Comment("Rescue players who fall out of the world instead of losing their inventory to the void.")
    public VoidRescue voidRescue = new VoidRescue();

    @Comment("Sleep handling: command blocking while in bed and night speed-up.")
    public Sleeping sleeping = new Sleeping();

    @Comment("Custom totem resurrection: arm a totem, survive one lethal hit, pay a cooldown.")
    public Resurrection resurrection = new Resurrection();

    @Comment("Compass display and item handling for /compass.")
    public Compass compass = new Compass();

    @Comment("Animated hue cycling for leather armour. Players still opt in with /armorcolor on.")
    public LeatherAnimation leatherAnimation = new LeatherAnimation();

    @Comment("Gradual world time speed from the day/night multipliers in the per-world rules.")
    public TimeRate timeRate = new TimeRate();

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
        if (worlds != null) {
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
                if (rule.fly != null && !rule.fly.isBlank() && rule.flightMode() == WorldRule.FlightMode.DEFAULT
                        && !rule.fly.trim().equalsIgnoreCase("DEFAULT")) {
                    warn.accept("worlds." + name + ": unknown fly mode '" + rule.fly + "' (DEFAULT, ALLOW or DENY)");
                }
                if (rule.godMode != null && !rule.godMode.isBlank() && rule.godMode() == WorldRule.GodMode.DEFAULT
                        && !rule.godMode.trim().equalsIgnoreCase("DEFAULT")) {
                    warn.accept("worlds." + name + ": unknown god mode '" + rule.godMode + "' (DEFAULT, ON or OFF)");
                }
                if (rule.flyAboveRoofHeight < 0) {
                    warn.accept("worlds." + name + ": negative fly-above-roof-height is ignored");
                }
                timeMultiplier(rule.dayTimeMultiplier, "day-time-multiplier", name, warn);
                timeMultiplier(rule.nightTimeMultiplier, "night-time-multiplier", name, warn);
                WorldFlags.spawnCategories(rule.blockMobSpawns,
                        message -> warn.accept("worlds." + name + ": " + message));
            }
        }
        if (resurrection != null && resurrection.enabled && resurrection.itemType() == null) {
            warn.accept("resurrection: unknown item '" + resurrection.item + "'");
        }
        if (resurrection != null) {
            for (String line : resurrection.effects) {
                if (ResurrectionEffects.parse(line) == null) {
                    warn.accept("resurrection: malformed effect '" + line + "' (expected EFFECT:seconds:amplifier)");
                }
            }
        }
        if (voidRescue != null && voidRescue.triggerOffset < 0) {
            warn.accept("void-rescue.trigger-offset: negative value clamped to 0");
        }
        if (timeRate != null && (timeRate.intervalTicks < 20 || timeRate.intervalTicks > 1200)) {
            warn.accept("time-rate.interval-ticks outside 20-1200 is clamped");
        }
    }

    @ConfigSerializable
    public static class Death {
        @Comment("Let players with atrium.worldrules.keepinventory keep their whole inventory on death.\n"
                + "The permission defaults to nobody, so existing servers keep vanilla behaviour.")
        public boolean keepInventoryPermission = false;

        @Comment("Let players with atrium.worldrules.keepxp keep their experience level on death.\n"
                + "The permission defaults to nobody, so existing servers keep vanilla behaviour.")
        public boolean keepExperiencePermission = false;

        @Comment("Respawn players automatically instead of showing the death screen.")
        public boolean autoRespawn = false;

        @Comment("Ticks to wait before the automatic respawn (1-200).")
        public int autoRespawnDelayTicks = 10;
    }

    @ConfigSerializable
    public static class VoidRescue {
        @Comment("Teleport players back to their last safe position when they fall below the world.")
        public boolean enabled = false;

        @Comment("Worlds where the rescue runs by name; an empty list means every world.")
        public List<String> worlds = new ArrayList<>();

        @Comment("Rescue when the player is this many blocks below the world's minimum height (0-256).\n"
                + "0 means the rescue starts right at the world's minimum height.")
        public int triggerOffset = 16;

        @Comment("Teleport to the world spawn instead of the last safe position under the player's feet.")
        public boolean toSpawn = false;

        @Comment("Seconds before one player can be rescued again (0-300). Stops a rescue loop in a broken world.")
        public int cooldownSeconds = 10;

        public boolean inWorld(String worldName) {
            if (worlds == null || worlds.isEmpty()) {
                return true;
            }
            for (String name : worlds) {
                if (name != null && name.equalsIgnoreCase(worldName)) {
                    return true;
                }
            }
            return false;
        }

        public int triggerOffset() {
            return Math.max(0, Math.min(256, triggerOffset));
        }

        public int cooldownSeconds() {
            return Math.max(0, Math.min(300, cooldownSeconds));
        }
    }

    @ConfigSerializable
    public static class Sleeping {
        @Comment("Cancel commands from a player lying in a bed (needs atrium.worldrules.sleeping.bypass to skip).")
        public boolean blockCommands = false;

        @Comment("Commands that are never blocked while in bed (without the leading slash).")
        public List<String> allowedCommands = new ArrayList<>(List.of("msg", "tell", "whisper", "reply", "r", "help"));

        @Comment("Speed up the night while players sleep. Off by default; the vanilla sleeping rule still applies.")
        public SpeedUp speedUp = new SpeedUp();

        public boolean allows(String commandRoot) {
            if (allowedCommands == null || commandRoot == null) {
                return false;
            }
            for (String allowed : allowedCommands) {
                if (allowed != null && allowed.equalsIgnoreCase(commandRoot)) {
                    return true;
                }
            }
            return false;
        }
    }

    @ConfigSerializable
    public static class SpeedUp {
        @Comment("Enable the night speed-up.")
        public boolean enabled = false;

        @Comment("Worlds where the speed-up runs by name; an empty list means every world.")
        public List<String> worlds = new ArrayList<>();

        @Comment("Fraction of the players in the world that must be sleeping before the night speeds up (0.1-1).")
        public double requiredPercent = 0.5;

        @Comment("Extra ticks added to the world time per second at 100% sleeping (1-1000).\n"
                + "Vanilla already advances 20 ticks per second, so 100 is roughly five times as fast.")
        public double ticksPerSecond = 100;

        @Comment("Only speed up between dusk and dawn. When false the day is sped up too.")
        public boolean onlyDuringNight = true;

        @Comment("Tell the sleeping players how many of them are sleeping when it is not enough yet.")
        public boolean inform = true;

        public boolean inWorld(String worldName) {
            if (worlds == null || worlds.isEmpty()) {
                return true;
            }
            for (String name : worlds) {
                if (name != null && name.equalsIgnoreCase(worldName)) {
                    return true;
                }
            }
            return false;
        }

        public double requiredPercent() {
            return Math.max(0.1, Math.min(1.0, requiredPercent));
        }

        public double ticksPerSecond() {
            return Math.max(1.0, Math.min(1000.0, ticksPerSecond));
        }
    }

    @ConfigSerializable
    public static class Resurrection {
        @Comment("Enable the custom totem resurrection system.")
        public boolean enabled = false;

        @Comment("Item that resurrects the holder. The plain material name, for example TOTEM_OF_UNDYING.")
        public String item = "TOTEM_OF_UNDYING";

        @Comment("Seconds the resurrection stays armed after a sneak right-click with the item (0-600).\n"
                + "0 arms it until it is used or the player leaves.")
        public int warmupSeconds = 10;

        @Comment("Seconds before the same player can be resurrected again (0-86400).")
        public int cooldownSeconds = 600;

        @Comment("Use up one item when it resurrects the player.")
        public boolean consumeItem = true;

        @Comment("Accept the item anywhere in the inventory, not only in the two hands.")
        public boolean anywhereInInventory = false;

        @Comment("Health restored after a resurrection (0.5-20).")
        public double health = 1.0;

        @Comment("Potion effects granted after a resurrection, as EFFECT:seconds:amplifier.")
        public List<String> effects = new ArrayList<>(List.of(
                "REGENERATION:45:1", "FIRE_RESISTANCE:40:0", "ABSORPTION:5:0"));

        @Comment("Also rescue from void damage. Without a safe position the player is sent to the world spawn.")
        public boolean protectFromVoid = false;

        @Comment("Play the totem sound and particle effect on the player.")
        public boolean showEffect = true;

        public org.bukkit.Material itemType() {
            String name = item == null ? "" : item.trim();
            if (name.isEmpty()) {
                return null;
            }
            return org.bukkit.Material.matchMaterial(name);
        }

        public int warmupSeconds() {
            return Math.max(0, Math.min(600, warmupSeconds));
        }

        public int cooldownSeconds() {
            return Math.max(0, Math.min(86400, cooldownSeconds));
        }

        public double health() {
            if (!Double.isFinite(health)) {
                return 1.0;
            }
            return Math.max(0.5, Math.min(20.0, health));
        }
    }

    @ConfigSerializable
    public static class Compass {
        @Comment("Show a boss bar with the direction and distance to the tracked player.")
        public boolean bossBar = false;

        @Comment("Boss bar colour: PINK, BLUE, RED, GREEN, YELLOW, PURPLE or WHITE.")
        public String bossBarColor = "BLUE";

        @Comment("Tracking another player needs a compass in a hand. Turn off to allow tracking without one.")
        public boolean requireItem = true;

        @Comment("Give a compass back after death when the player had one and it was lost.")
        public boolean recoverOnDeath = false;

        public net.kyori.adventure.bossbar.BossBar.Color barColor() {
            try {
                return net.kyori.adventure.bossbar.BossBar.Color.valueOf(
                        bossBarColor == null ? "BLUE" : bossBarColor.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return net.kyori.adventure.bossbar.BossBar.Color.BLUE;
            }
        }
    }

    @ConfigSerializable
    public static class LeatherAnimation {
        @Comment("Enable hue cycling for leather armour. Off by default; every player opts in with /armorcolor on.")
        public boolean enabled = false;

        @Comment("Ticks between colour updates per player (5-200). The task only touches the wearer's own armour.")
        public int refreshTicks = 20;

        @Comment("Hue change per update in degrees (1-120). Higher values cycle faster.")
        public int hueStep = 12;

        @Comment("Saturation of the animated colour (0-100).")
        public int saturation = 85;

        @Comment("Brightness of the animated colour (0-100).")
        public int brightness = 100;

        public int refreshTicks() {
            return clamp(refreshTicks, 5, 200);
        }

        public int hueStep() {
            return clamp(hueStep, 1, 120);
        }

        public int saturation() {
            return clamp(saturation, 0, 100);
        }

        public int brightness() {
            return clamp(brightness, 0, 100);
        }
    }

    @ConfigSerializable
    public static class TimeRate {
        @Comment("Enable the day/night speed multipliers from the per-world rules. Off by default.")
        public boolean enabled = false;

        @Comment("Ticks between time adjustments (20-1200). 100 means the multipliers are applied every 5 seconds.")
        public int intervalTicks = 100;

        public int intervalTicks() {
            return clamp(intervalTicks, 20, 1200);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void timeMultiplier(double value, String option, String world,
            java.util.function.Consumer<String> warn) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            warn.accept("worlds." + world + ": " + option + " outside 0-100 is clamped");
        }
    }
}
