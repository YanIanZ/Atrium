package dev.yanianz.atrium.module.worldrules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.GameMode;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * Rules for one world ({@code worlds.<name>} in worldrules.yml). Every value is concrete; the entry for
 * {@code "*"} is used for a world without its own entry.
 */
@ConfigSerializable
public class WorldRule {
    /** Value used when neither the world nor {@code "*"} is configured. Never mutated. */
    static final WorldRule NONE = new WorldRule();

    @Comment("Multiply fall damage by this (0-100). 0 disables fall damage; ignored when disable-fall-damage is true.")
    public double fallDamageMultiplier = 1.0;

    @Comment("Disable fall damage completely in this world.")
    public boolean disableFallDamage = false;

    @Comment("Multiply hunger drain by this (0-100). 0 stops hunger drain; eating still works.")
    public double hungerMultiplier = 1.0;

    @Comment("Stop hunger drain completely. Eating and saturation regeneration still work.")
    public boolean disableHunger = false;

    @Comment("Ender pearl cooldown in seconds (0-3600). 0 keeps the vanilla cooldown.")
    public int enderPearlCooldownSeconds = 0;

    @Comment("Game mode forced when a player enters this world: survival, creative, adventure or spectator.\n"
            + "Empty disables it. Players with atrium.worldrules.gamemode.bypass are exempt.")
    public String forceGameMode = "";

    @Comment("Prevent players from starting to glide with an elytra in this world.")
    public boolean disableElytra = false;

    @Comment("Prevent damage between two players in this world. Projectiles are checked too.")
    public boolean disablePvp = false;

    @Comment("Flight in this world: DEFAULT, ALLOW or DENY.\n"
            + "ALLOW lets survival players fly, DENY stops players from starting or keeping flight.\n"
            + "Creative/spectator keep their flight and atrium.worldrules.fly.bypass is exempt.")
    public String fly = "DEFAULT";

    @Comment("World-wide damage immunity: DEFAULT, ON or OFF. ON protects every player in this world.")
    public String godMode = "DEFAULT";

    @Comment("Deny flight above this Y level; 0 disables the limit. The Nether roof is at Y=127.\n"
            + "Only affects players who are flying or gliding when they cross the limit.")
    public int flyAboveRoofHeight = 0;

    @Comment("Block ender pearl teleports in this world.")
    public boolean disableEnderPearls = false;

    @Comment("Stop players from dropping items in this world.")
    public boolean disableItemDrop = false;

    @Comment("Stop players from picking up items in this world.")
    public boolean disableItemPickup = false;

    @Comment("Stop fire from spreading to other blocks. Existing fire keeps burning.")
    public boolean disableFireSpread = false;

    @Comment("Stop explosions from breaking blocks. Entity damage from the explosion is unchanged.")
    public boolean disableExplosionBlockDamage = false;

    @Comment("Stop leaves from decaying in this world.")
    public boolean disableLeafDecay = false;

    @Comment("Stop players from trampling farmland in this world.")
    public boolean disableCropTrample = false;

    @Comment("Stop new nether or end portals from being created by fire in this world.")
    public boolean disablePortalCreation = false;

    @Comment("Mob categories that never spawn in this world: MONSTER, ANIMAL, WATER, AMBIENT, OTHER or ALL.")
    public List<String> blockMobSpawns = new ArrayList<>();

    @Comment("Also block spawner and trial spawner spawns in this world.")
    public boolean blockSpawnerMobs = false;

    @Comment("How fast daytime passes in this world (0-100). 1 is vanilla, 2 twice as fast, 0.5 half speed, 0 pauses daytime.")
    public double dayTimeMultiplier = 1.0;

    @Comment("How fast nighttime passes in this world (0-100). Same scale as day-time-multiplier.")
    public double nightTimeMultiplier = 1.0;

    @Comment("End gateway behaviour: NORMAL, DISABLED or FIXED.")
    public String endGateway = "NORMAL";

    @Comment("Target for FIXED gateways: world, x, y, z, yaw, pitch (for example \"world_nether, 0, 64, 0, 0, 0\").")
    public String gatewayTarget = "";

    @Comment("Chance (0-100) that a death drop actually falls on the ground in this world.\n"
            + "0 keeps every item, 100 is vanilla. Ignored when the inventory is kept.")
    public double deathDropPercent = 100;

    @Comment("Keep the inventory of a player who dies in this world, without needing a permission.")
    public boolean keepInventoryOnDeath = false;

    @Comment("Keep the experience level of a player who dies in this world, without needing a permission.")
    public boolean keepExperienceOnDeath = false;

    double fallDamageMultiplier() {
        return multiplier(fallDamageMultiplier);
    }

    double hungerMultiplier() {
        return multiplier(hungerMultiplier);
    }

    int enderPearlCooldownSeconds() {
        return Math.max(0, Math.min(3600, enderPearlCooldownSeconds));
    }

    /** Percentage of the death drops that still falls on the ground (0-100). */
    double deathDropPercent() {
        return multiplier(deathDropPercent);
    }

    FlightMode flightMode() {
        return FlightMode.parse(fly);
    }

    GodMode godMode() {
        return GodMode.parse(godMode);
    }

    /** Configured roof limit, or 0 when the limit is disabled. */
    int flyAboveRoofHeight() {
        return Math.max(0, flyAboveRoofHeight);
    }

    double dayTimeMultiplier() {
        return timeMultiplier(dayTimeMultiplier);
    }

    double nightTimeMultiplier() {
        return timeMultiplier(nightTimeMultiplier);
    }

    private static double timeMultiplier(double value) {
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    /** Forced game mode, or {@code null} when disabled or when the configured name is invalid. */
    GameMode forcedGameMode() {
        String name = forceGameMode == null ? "" : forceGameMode.trim();
        if (name.isEmpty()) {
            return null;
        }
        try {
            return GameMode.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            try {
                return GameMode.getByValue(Integer.parseInt(name));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    GatewayMode gatewayMode() {
        return GatewayMode.parse(endGateway);
    }

    private static double multiplier(double value) {
        if (!Double.isFinite(value)) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(100.0, value));
    }

    enum GatewayMode {
        NORMAL,
        DISABLED,
        FIXED;

        static GatewayMode parse(String value) {
            if (value == null) {
                return NORMAL;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return NORMAL;
            }
        }
    }

    enum FlightMode {
        DEFAULT,
        ALLOW,
        DENY;

        static FlightMode parse(String value) {
            if (value == null) {
                return DEFAULT;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DEFAULT;
            }
        }
    }

    enum GodMode {
        DEFAULT,
        ON,
        OFF;

        static GodMode parse(String value) {
            if (value == null) {
                return DEFAULT;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return DEFAULT;
            }
        }
    }
}
