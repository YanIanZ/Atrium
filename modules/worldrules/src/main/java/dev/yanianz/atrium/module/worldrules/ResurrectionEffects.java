package dev.yanianz.atrium.module.worldrules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.potion.PotionEffectType;

/** Parsing of the resurrection effect lines ({@code EFFECT:seconds:amplifier}). */
final class ResurrectionEffects {
    record Spec(String name, int seconds, int amplifier) {}

    private ResurrectionEffects() {}

    /** Returns {@code null} for a malformed line so validation can report it. */
    static Spec parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.trim().split(":");
        if (parts.length < 2 || parts.length > 3) {
            return null;
        }
        String name = parts[0].trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            return null;
        }
        int seconds;
        int amplifier = 0;
        try {
            seconds = Integer.parseInt(parts[1].trim());
            if (parts.length == 3) {
                amplifier = Integer.parseInt(parts[2].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (seconds <= 0 || seconds > 86400 || amplifier < 0 || amplifier > 255) {
            return null;
        }
        return new Spec(name, seconds, amplifier);
    }

    static List<Spec> parseAll(List<String> lines) {
        List<Spec> specs = new ArrayList<>();
        if (lines == null) {
            return specs;
        }
        for (String line : lines) {
            Spec spec = parse(line);
            if (spec != null) {
                specs.add(spec);
            }
        }
        return specs;
    }

    /** The Bukkit effect type, or {@code null} when this server does not know the name. */
    static PotionEffectType resolve(Spec spec) {
        if (spec == null) {
            return null;
        }
        String name = spec.name();
        int colon = name.indexOf(':');
        String key = (colon >= 0 ? name.substring(colon + 1) : name).toLowerCase(Locale.ROOT);
        try {
            return org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(key));
        } catch (RuntimeException e) {
            // A key that is not a valid namespaced key means the admin typed something unknown.
            return null;
        }
    }
}
