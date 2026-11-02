package dev.yanianz.atrium.module.npcs;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Pose names used by {@code /npc pose}. The canonical spelling always matches a Bukkit
 * {@link org.bukkit.entity.Pose} constant in lower case, so the native backend can apply it directly.
 * Aliases only exist in commands and in stored value normalisation. Pure: no server access, so the
 * accepted/rejected rules are unit-tested.
 */
final class NpcPose {
    /** Bukkit {@code Pose.STANDING}. */
    static final String STANDING = "standing";
    /** Bukkit {@code Pose.SNEAKING}; also what players call "crouching". */
    static final String SNEAKING = "sneaking";

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("standing", STANDING),
            Map.entry("stand", STANDING),
            Map.entry("default", STANDING),
            Map.entry("none", STANDING),
            Map.entry("sneaking", SNEAKING),
            Map.entry("sneak", SNEAKING),
            Map.entry("crouching", SNEAKING),
            Map.entry("crouch", SNEAKING),
            Map.entry("sitting", "sitting"),
            Map.entry("sit", "sitting"),
            Map.entry("sleeping", "sleeping"),
            Map.entry("sleep", "sleeping"),
            Map.entry("lying", "sleeping"),
            Map.entry("lay", "sleeping"),
            Map.entry("swimming", "swimming"),
            Map.entry("swim", "swimming"),
            Map.entry("crawling", "swimming"),
            Map.entry("crawl", "swimming"),
            Map.entry("spin_attack", "spin_attack"),
            Map.entry("spin", "spin_attack"),
            Map.entry("fall_flying", "fall_flying"),
            Map.entry("elytra", "fall_flying"),
            Map.entry("long_jumping", "long_jumping"),
            Map.entry("long_jump", "long_jumping"),
            Map.entry("dying", "dying"),
            Map.entry("death", "dying"),
            Map.entry("sliding", "sliding"),
            Map.entry("slide", "sliding"),
            Map.entry("shooting", "shooting"),
            Map.entry("shoot", "shooting"),
            Map.entry("inhaling", "inhaling"),
            Map.entry("inhale", "inhaling"));

    /** Citizens exposes only a sneaking flag, so these are the poses its backend can really apply. */
    static final List<String> CITIZENS_POSES = List.of(STANDING, SNEAKING);

    private NpcPose() {}

    /** Canonical lower-case pose name for a user-typed value (aliases included); empty when unknown. */
    static Optional<String> parse(String input) {
        if (input == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ALIASES.get(input.trim().toLowerCase(Locale.ROOT)));
    }

    /** Parses the value or falls back, used to normalise stored values written by older versions. */
    static String canonicalOr(String input, String fallback) {
        return parse(input).orElse(fallback);
    }

    /** Sorted suggestions, limited to the poses an engine can apply. */
    static List<String> suggestions(Collection<String> supported) {
        return supported.stream().map(value -> value.toLowerCase(Locale.ROOT)).sorted().toList();
    }
}
