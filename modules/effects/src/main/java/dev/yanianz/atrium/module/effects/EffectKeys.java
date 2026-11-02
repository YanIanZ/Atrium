package dev.yanianz.atrium.module.effects;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.entity.EntityType;
import org.jspecify.annotations.Nullable;

/** Event ids understood by the module, plus the {@code kill.<entity>} lookup rule. */
public final class EffectKeys {
    public static final String FIRST_JOIN = "first-join";
    public static final String JOIN = "join";
    public static final String QUIT = "quit";
    public static final String DEATH = "death";
    public static final String KILL = "kill";
    public static final String TELEPORT_DEPART = "teleport-depart";
    public static final String TELEPORT_ARRIVE = "teleport-arrive";
    public static final String LEVEL_UP = "level-up";
    public static final String ADVANCEMENT = "advancement";
    public static final String RESPAWN = "respawn";

    /** Events that have no Bukkit event in Atrium and are therefore not implemented. */
    public static final List<String> UNSUPPORTED = List.of("rank-up", "playtime-reward");

    public static final List<String> ALL = List.of(
            FIRST_JOIN, JOIN, QUIT, DEATH, KILL,
            TELEPORT_DEPART, TELEPORT_ARRIVE, LEVEL_UP, ADVANCEMENT, RESPAWN);

    private EffectKeys() {}

    /** Lower-cases and trims a config key; replaces spaces and underscores with dashes. */
    public static String normalize(String raw) {
        return raw.strip().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
    }

    /**
     * Chooses the effect for an entity death: {@code kill.<ENTITY_TYPE>} wins over {@code kill}, so a
     * server can give a boss its own effect while keeping the generic one for everything else.
     */
    public static @Nullable EffectStyle forKill(
            Map<String, EffectStyle> effects, EntityType type, @Nullable EffectStyle fallback) {
        EffectStyle specific = effects.get(KILL + "." + type.name().toLowerCase(Locale.ROOT));
        return specific != null ? specific : fallback;
    }

    /** Whether the id is one of the implemented events (including {@code kill.<entity>} variants). */
    public static boolean isKnown(String id) {
        String normalized = normalize(id);
        return ALL.contains(normalized) || normalized.startsWith(KILL + ".");
    }

    /** Suggests configured keys plus the built-in ids, for tab completion. */
    public static List<String> suggestions(Map<String, EffectStyle> effects) {
        List<String> out = new java.util.ArrayList<>(ALL);
        for (String key : effects.keySet()) {
            if (!out.contains(key)) {
                out.add(key);
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
