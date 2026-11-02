package dev.yanianz.atrium.module.recipes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure enchantment policy: which enchantments are disabled and their maximum levels. Keys are normalised to
 * the Bukkit form {@code minecraft:sharpness}, so {@code sharpness}, {@code SHARPNESS} and
 * {@code minecraft:sharpness} all mean the same entry.
 *
 * <p>{@link #allowedLevel(String, int)} returns {@code 0} for a disabled enchantment. Callers remove the
 * enchantment in that case instead of forcing level 1.
 */
public final class EnchantRules {
    public static final int NO_CAP = 0;

    private final Set<String> disabled;
    private final Map<String, Integer> maxLevels;

    private EnchantRules(Set<String> disabled, Map<String, Integer> maxLevels) {
        this.disabled = disabled;
        this.maxLevels = maxLevels;
    }

    public static EnchantRules from(List<String> disabled, Map<String, Integer> maxLevels) {
        Set<String> disabledKeys = new LinkedHashSet<>();
        if (disabled != null) {
            for (String raw : disabled) {
                String key = normalize(raw);
                if (!key.isEmpty()) {
                    disabledKeys.add(key);
                }
            }
        }
        Map<String, Integer> caps = new LinkedHashMap<>();
        if (maxLevels != null) {
            maxLevels.forEach((raw, level) -> {
                String key = normalize(raw);
                if (!key.isEmpty() && level != null && level >= 1) {
                    caps.put(key, Math.min(level, 255));
                }
            });
        }
        return new EnchantRules(Collections.unmodifiableSet(disabledKeys),
                Collections.unmodifiableMap(caps));
    }

    public static EnchantRules empty() {
        return new EnchantRules(Set.of(), Map.of());
    }

    /** Adds the {@code minecraft:} namespace when the admin wrote a plain name. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            return "";
        }
        return key.indexOf(':') < 0 ? "minecraft:" + key : key;
    }

    public boolean isActive() {
        return !disabled.isEmpty() || !maxLevels.isEmpty();
    }

    public Set<String> disabled() {
        return disabled;
    }

    public Map<String, Integer> maxLevels() {
        return maxLevels;
    }

    public boolean isDisabled(String key) {
        return disabled.contains(normalize(key));
    }

    public int cap(String key) {
        Integer cap = maxLevels.get(normalize(key));
        return cap == null ? NO_CAP : cap;
    }

    /** {@code 0} means the enchantment must be removed; otherwise the level after applying the cap. */
    public int allowedLevel(String key, int level) {
        String normalized = normalize(key);
        if (disabled.contains(normalized)) {
            return 0;
        }
        Integer cap = maxLevels.get(normalized);
        if (cap == null || level <= cap) {
            return Math.max(0, level);
        }
        return cap;
    }

    /** Filters and caps a map of normalised keys to levels; used by the pure tests and by result builders. */
    public Map<String, Integer> sanitize(Map<String, Integer> levels) {
        Map<String, Integer> result = new LinkedHashMap<>();
        levels.forEach((key, level) -> {
            if (level == null) {
                return;
            }
            int allowed = allowedLevel(key, level);
            if (allowed > 0) {
                result.put(normalize(key), allowed);
            }
        });
        return Collections.unmodifiableMap(result);
    }
}
