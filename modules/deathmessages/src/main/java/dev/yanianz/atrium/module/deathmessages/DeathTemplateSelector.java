package dev.yanianz.atrium.module.deathmessages;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure selection of the template keys for a death, most specific first. The listener turns a Bukkit
 * {@code DamageSource} into a {@link Damage} record; keeping the selection free of Bukkit types makes the
 * precedence rules (environmental cause &gt; player &gt; mob type &gt; projectile &gt; fallback) testable.
 */
public final class DeathTemplateSelector {
    private static final Set<String> PROJECTILES = Set.of(
            "arrow", "spectral_arrow", "trident", "thrown", "fireball", "small_fireball", "dragon_fireball",
            "wither_skull", "wind_charge", "fireworks", "snowball", "egg", "ender_pearl", "llama_spit", "shulker_bullet");
    private static final Set<String> EXPLOSIONS = Set.of("explosion", "player_explosion", "bad_respawn_point");

    private DeathTemplateSelector() {}

    /**
     * The damage information needed to choose a template.
     *
     * @param typeKey damage type key without namespace ({@code fall}, {@code lava}, {@code arrow}, ...)
     * @param directEntityKey entity type key of the direct source ({@code arrow}, ...), or {@code null}
     * @param causingEntityKey entity type key of the causing entity ({@code zombie}, ...), or {@code null}
     * @param causingIsPlayer whether the causing entity is another player
     */
    public record Damage(String typeKey, String directEntityKey, String causingEntityKey, boolean causingIsPlayer) {
        public Damage {
            typeKey = normalize(typeKey);
            directEntityKey = normalizeOrNull(directEntityKey);
            causingEntityKey = normalizeOrNull(causingEntityKey);
        }
    }

    /** Candidate template keys, most specific first; the last entry is always {@code fallback}. */
    public static List<String> candidates(Damage damage) {
        Set<String> keys = new LinkedHashSet<>();
        String type = normalize(damage.typeKey());

        switch (type) {
            case "fall", "falling_anvil", "falling_block", "falling_stalactite", "fly_into_wall" -> keys.add("fall");
            case "lava" -> keys.add("lava");
            case "drown" -> keys.add("drowning");
            default -> {
                if (EXPLOSIONS.contains(type)) {
                    keys.add("explosion");
                }
            }
        }

        if (damage.causingIsPlayer()) {
            keys.add("player");
            keys.add("fallback");
            return List.copyOf(keys);
        }

        if (damage.causingEntityKey() != null) {
            keys.add("mob." + damage.causingEntityKey());
            keys.add("mob");
        }
        if (PROJECTILES.contains(type)
                || (damage.directEntityKey() != null && PROJECTILES.contains(damage.directEntityKey()))) {
            keys.add("projectile");
        }
        keys.add("fallback");
        return List.copyOf(keys);
    }

    /** Entity type keys from Bukkit are namespaced ({@code minecraft:zombie}); use the plain key. */
    public static String normalize(String key) {
        if (key == null || key.isBlank()) {
            return "generic";
        }
        String value = key.trim().toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        return value.replace(' ', '_');
    }

    private static String normalizeOrNull(String key) {
        return key == null || key.isBlank() ? null : normalize(key);
    }
}
