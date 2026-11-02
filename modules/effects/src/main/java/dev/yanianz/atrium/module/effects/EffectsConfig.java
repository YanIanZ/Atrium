package dev.yanianz.atrium.module.effects;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.meta.Comment;

/**
 * plugins/Atrium/modules/effects.yml
 *
 * <p>Event keys are fixed and documented below. {@code kill} also accepts {@code kill.<ENTITY_TYPE>}
 * entries that override the generic kill effect for one entity type.
 */
@ConfigSerializable
public class EffectsConfig {
    @Comment("Event effects, one entry per event. Supported keys: first-join, join, quit, death, kill,\n"
            + "teleport-depart, teleport-arrive, level-up, advancement, respawn.\n"
            + "A 'kill.<ENTITY_TYPE>' entry (for example kill.ZOMBIE) overrides 'kill' for that entity type.\n"
            + "Rank-up and playtime rewards are not implemented because those modules publish no event.")
    public Map<String, EventEffect> events = defaults();

    @Comment("How often, in ticks, idle fixed effects are checked so a timer can start when a player\n"
            + "walks into range. Active timers stop by themselves when nobody is near.")
    public int fixedCheckIntervalTicks = 40;

    @Comment("A fixed effect renders only while a player is within this many blocks. The check ignores\n"
            + "height so caves and buildings still activate the effect.")
    public double fixedViewRadius = 32.0;

    @Comment("Upper limit of fixed effects on the server (1-512). /effects fixed create refuses more.")
    public int maxFixedEffects = 128;

    /** Adds event keys that were added to this class after the server's config file was written. */
    public void fillMissingEvents() {
        if (events == null) {
            events = new LinkedHashMap<>();
        }
        defaults().forEach(events::putIfAbsent);
    }

    /** One event effect. Every sub-section is optional; an empty effect is skipped. */
    @ConfigSerializable
    public static class EventEffect {
        @Comment("Enable or disable this event effect without deleting its settings.")
        public boolean enabled = true;

        @Comment("Permission a player must hold to receive this effect. Empty means everyone.")
        public String permission = "";

        @Comment("Seconds before the same player can receive this effect again. 0 disables the cooldown.")
        public int cooldownSeconds = 0;

        @Comment("Only players within this many blocks see the particles and hear the sound (4-64).\n"
                + "Titles and action bars always go to the affected player only.")
        public double viewRadius = 32.0;

        @Comment("Particle animation. Leave 'particle' empty for an effect without particles.")
        public Particles particle = new Particles();

        @Comment("Sound played at the effect location.")
        public Sound sound = new Sound();

        @Comment("Title and subtitle (MiniMessage). Leave both empty to disable.")
        public Title title = new Title();

        @Comment("Action bar text (MiniMessage) shown to the affected player. Empty disables it.")
        public String actionBar = "";

        @Comment("Optional firework launched at the effect location.")
        public Firework firework = new Firework();

        @Comment("For the kill event: only fire for these entity types (for example ZOMBIE, PLAYER).\n"
                + "Empty means every entity type.")
        public List<String> entityTypes = new ArrayList<>();
    }

    /** Particle shape and its animation timing. */
    @ConfigSerializable
    public static class Particles {
        @Comment("Shape: burst, ring, sphere, column or spiral.")
        public String shape = "spiral";

        @Comment("Bukkit particle name, e.g. FLAME, END_ROD, HAPPY_VILLAGER or DUST.\n"
                + "Empty disables particles for this event. Particles that need block or item data are\n"
                + "rejected at load time.")
        public String particle = "";

        @Comment("Colour for DUST, DUST_COLOR_TRANSITION and ENTITY_EFFECT particles, as #RRGGBB.")
        public String colour = "";

        @Comment("Fade colour for DUST_COLOR_TRANSITION. Empty reuses 'colour'.")
        public String colour2 = "";

        @Comment("Dust size for DUST and DUST_COLOR_TRANSITION particles (0.1-4).")
        public double size = 1.0;

        @Comment("Horizontal radius of the shape in blocks (0.05-32).")
        public double radius = 1.2;

        @Comment("Vertical extent used by the column and spiral shapes in blocks (0-32).")
        public double height = 2.0;

        @Comment("Positions per frame (1-256). Keep this low: every position is sent to every viewer.")
        public int points = 24;

        @Comment("Particles spawned per position per frame (1-8).")
        public int count = 1;

        @Comment("Extra speed passed to the particle, usually 0.")
        public double speed = 0.0;

        @Comment("Ticks between animation frames (1-40).")
        public int intervalTicks = 2;

        @Comment("Frames before the animation stops (1-400). Duration is intervalTicks * frames.")
        public int frames = 20;

        @Comment("Degrees the shape turns per frame (0-90).")
        public double rotation = 15.0;
    }

    /** A sound: enum name or namespaced key. */
    @ConfigSerializable
    public static class Sound {
        @Comment("Sound name, e.g. ENTITY_PLAYER_LEVELUP, or a namespaced key such as\n"
                + "minecraft:entity.player.levelup. Empty disables the sound.")
        public String key = "";

        @Comment("Volume multiplier (0-10).")
        public double volume = 1.0;

        @Comment("Pitch multiplier (0.5-2).")
        public double pitch = 1.0;

        @Comment("Sound category: MASTER, PLAYERS, BLOCKS, UI, ...")
        public String category = "MASTER";
    }

    /** Title and subtitle in admin MiniMessage. */
    @ConfigSerializable
    public static class Title {
        @Comment("Title line (MiniMessage, trusted). <player> is replaced with the player name.")
        public String title = "";

        @Comment("Subtitle line (MiniMessage, trusted). <player> is replaced with the player name.")
        public String subtitle = "";

        @Comment("Fade-in time in ticks (0-200).")
        public int fadeInTicks = 10;

        @Comment("Stay time in ticks (0-400).")
        public int stayTicks = 40;

        @Comment("Fade-out time in ticks (0-200).")
        public int fadeOutTicks = 10;
    }

    /** Optional firework. */
    @ConfigSerializable
    public static class Firework {
        @Comment("Launch a firework at the effect location.")
        public boolean enabled = false;

        @Comment("Firework shape: BALL, BALL_LARGE, STAR, BURST or CREEPER.")
        public String type = "BALL";

        @Comment("Explosion colours as #RRGGBB. Empty means white.")
        public List<String> colours = new ArrayList<>(List.of("#5fb3a1", "#ffffff"));

        @Comment("Fade colours as #RRGGBB.")
        public List<String> fade = new ArrayList<>();

        @Comment("Flight power (0-3) used until the firework detonates.")
        public int power = 1;

        @Comment("Ticks before the firework is detonated and removed (0-40).")
        public int detonateAfterTicks = 8;

        @Comment("Twinkle effect.")
        public boolean flicker = false;

        @Comment("Trail effect.")
        public boolean trail = false;
    }

    /** Default event set. Kept in one place so the module has something to preview after installing. */
    static Map<String, EventEffect> defaults() {
        Map<String, EventEffect> map = new LinkedHashMap<>();

        EventEffect firstJoin = new EventEffect();
        firstJoin.particle = particles("spiral", "END_ROD", "", "", 1.2, 2.5, 36, 3, 24, 12.0);
        firstJoin.sound = sound("ENTITY_PLAYER_LEVELUP", 1.0, 1.2, "PLAYERS");
        firstJoin.title = title("<primary><bold>Welcome to the server!</bold></primary>",
                "<muted>Have fun, <highlight><player></highlight>!", 10, 50, 10);
        firstJoin.firework = firework(true, "BALL", List.of("#5fb3a1", "#ffffff"), List.of("#3b7f9a"));
        firstJoin.actionBar = "";
        map.put(EffectKeys.FIRST_JOIN, firstJoin);

        EventEffect join = new EventEffect();
        join.enabled = false;
        join.particle = particles("ring", "HAPPY_VILLAGER", "", "", 1.0, 0.5, 16, 2, 8, 8.0);
        join.sound = sound("ENTITY_PLAYER_LEVELUP", 0.6, 1.4, "PLAYERS");
        map.put(EffectKeys.JOIN, join);

        EventEffect quit = new EventEffect();
        quit.enabled = false;
        quit.particle = particles("burst", "SMOKE", "", "", 0.8, 1.0, 16, 2, 10, 0.0);
        quit.sound = sound("BLOCK_NOTE_BLOCK_BASS", 0.6, 0.8, "PLAYERS");
        map.put(EffectKeys.QUIT, quit);

        EventEffect death = new EventEffect();
        death.particle = particles("burst", "LARGE_SMOKE", "", "", 1.0, 1.2, 20, 3, 12, 0.0);
        death.sound = sound("ENTITY_PLAYER_HURT", 1.0, 0.8, "PLAYERS");
        map.put(EffectKeys.DEATH, death);

        EventEffect kill = new EventEffect();
        kill.particle = particles("burst", "CRIT", "", "", 0.9, 1.0, 18, 2, 10, 0.0);
        kill.sound = sound("ENTITY_PLAYER_ATTACK_CRIT", 0.8, 1.0, "PLAYERS");
        map.put(EffectKeys.KILL, kill);

        EventEffect depart = new EventEffect();
        depart.enabled = false;
        depart.particle = particles("burst", "PORTAL", "", "", 0.8, 1.0, 16, 2, 10, 0.0);
        depart.sound = sound("ENTITY_ENDERMAN_TELEPORT", 0.6, 1.2, "PLAYERS");
        map.put(EffectKeys.TELEPORT_DEPART, depart);

        EventEffect arrive = new EventEffect();
        arrive.particle = particles("ring", "END_ROD", "", "", 1.0, 0.5, 20, 2, 12, 10.0);
        arrive.sound = sound("ENTITY_ENDERMAN_TELEPORT", 0.6, 1.5, "PLAYERS");
        map.put(EffectKeys.TELEPORT_ARRIVE, arrive);

        EventEffect levelUp = new EventEffect();
        levelUp.particle = particles("spiral", "HAPPY_VILLAGER", "", "", 1.0, 2.0, 28, 3, 20, 14.0);
        levelUp.sound = sound("ENTITY_PLAYER_LEVELUP", 1.0, 1.0, "PLAYERS");
        map.put(EffectKeys.LEVEL_UP, levelUp);

        EventEffect advancement = new EventEffect();
        advancement.enabled = false;
        advancement.particle = particles("burst", "FIREWORK", "", "", 1.2, 1.5, 24, 3, 14, 0.0);
        advancement.sound = sound("UI_TOAST_CHALLENGE_COMPLETE", 1.0, 1.0, "UI");
        advancement.firework = firework(true, "BURST", List.of("#ffd166", "#ffffff"), List.of("#ef8354"));
        map.put(EffectKeys.ADVANCEMENT, advancement);

        EventEffect respawn = new EventEffect();
        respawn.particle = particles("ring", "END_ROD", "", "", 1.0, 0.5, 20, 2, 12, 10.0);
        respawn.sound = sound("BLOCK_RESPAWN_ANCHOR_SET_SPAWN", 0.8, 1.2, "BLOCKS");
        map.put(EffectKeys.RESPAWN, respawn);

        return map;
    }

    private static Particles particles(
            String shape, String particle, String colour, String colour2,
            double radius, double height, int points, int intervalTicks, int frames, double rotation) {
        Particles settings = new Particles();
        settings.shape = shape;
        settings.particle = particle;
        settings.colour = colour;
        settings.colour2 = colour2;
        settings.radius = radius;
        settings.height = height;
        settings.points = points;
        settings.intervalTicks = intervalTicks;
        settings.frames = frames;
        settings.rotation = rotation;
        return settings;
    }

    private static Sound sound(String key, double volume, double pitch, String category) {
        Sound settings = new Sound();
        settings.key = key;
        settings.volume = volume;
        settings.pitch = pitch;
        settings.category = category;
        return settings;
    }

    private static Title title(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        Title settings = new Title();
        settings.title = title;
        settings.subtitle = subtitle;
        settings.fadeInTicks = fadeIn;
        settings.stayTicks = stay;
        settings.fadeOutTicks = fadeOut;
        return settings;
    }

    private static Firework firework(boolean enabled, String type, List<String> colours, List<String> fade) {
        Firework settings = new Firework();
        settings.enabled = enabled;
        settings.type = type;
        settings.colours = new ArrayList<>(colours);
        settings.fade = new ArrayList<>(fade);
        return settings;
    }
}
