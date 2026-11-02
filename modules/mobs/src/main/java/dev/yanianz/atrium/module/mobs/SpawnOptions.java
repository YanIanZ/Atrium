package dev.yanianz.atrium.module.mobs;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Options of one {@code /spawnmob} call: the space separated tokens after the type, for example
 * {@code hp:40 t:30 name:Bob mainhand:IRON_SWORD nogravity}.
 *
 * <p>Every token is {@code key}, {@code key:value}, {@code key-value} or {@code key{value}}; a leading
 * {@code -} or {@code !} turns a boolean option off. Values are clamped to sane ranges here so the
 * spawn code never has to. Pure: no Bukkit types, so the parsing rules are unit tested.
 */
record SpawnOptions(
        double lifetimeSeconds,
        double health,
        @Nullable String name,
        boolean hideName,
        boolean glow,
        boolean silent,
        boolean invulnerable,
        boolean noGravity,
        boolean noAi,
        boolean onFire,
        boolean charged,
        boolean dinnerbone,
        boolean jeb,
        boolean saddle,
        boolean chest,
        @Nullable Boolean baby,
        @Nullable Boolean pickup,
        double speed,
        double scale,
        @Nullable String target,
        Map<Slot, String> equipment,
        boolean persistent) {

    static final double NO_LIFETIME = 0;
    static final double NO_HEALTH = 0;
    static final double NO_SPEED = -1;
    static final double NO_SCALE = -1;

    /** Equipment slots an option can fill; the value is the material name written by the admin. */
    enum Slot {
        HEAD("head", "helmet"),
        CHEST("chestplate", "torso"),
        LEGS("legs", "leggings"),
        BOOTS("boots"),
        MAIN_HAND("mainhand", "hand"),
        OFF_HAND("offhand");

        private final List<String> names;

        Slot(String... names) {
            this.names = List.of(names);
        }

        static @Nullable Slot byName(String key) {
            for (Slot slot : values()) {
                if (slot.names.contains(key)) {
                    return slot;
                }
            }
            return null;
        }
    }

    static final SpawnOptions NONE = new Builder().build();

    SpawnOptions {
        equipment = Map.copyOf(equipment);
    }

    boolean isEmpty() {
        return equals(NONE);
    }

    boolean hasLifetime() {
        return lifetimeSeconds > 0;
    }

    /**
     * Parses the option string. Throws {@link IllegalArgumentException} whose message is the offending
     * token when a key or value is not understood.
     */
    static SpawnOptions parse(@Nullable String text, int maxLifetimeSeconds) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        Builder builder = new Builder();
        for (String token : split(text)) {
            apply(builder, token, Math.max(1, maxLifetimeSeconds));
        }
        return builder.build();
    }

    /** Splits on whitespace but keeps {@code name{two words}} together. */
    static List<String> split(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && depth > 0) {
                depth--;
            }
            if (Character.isWhitespace(c) && depth == 0) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static void apply(Builder builder, String token, int maxLifetime) {
        String raw = token.strip();
        if (raw.isEmpty()) {
            return;
        }
        boolean negated = raw.charAt(0) == '-' || raw.charAt(0) == '!';
        String body = negated ? raw.substring(1) : raw;
        if (body.isEmpty()) {
            throw new IllegalArgumentException(token);
        }
        int separator = firstSeparator(body);
        String key;
        String value = null;
        if (separator < 0) {
            key = body.toLowerCase(Locale.ROOT);
        } else {
            key = body.substring(0, separator).toLowerCase(Locale.ROOT);
            char kind = body.charAt(separator);
            if (kind == '{') {
                if (!body.endsWith("}")) {
                    throw new IllegalArgumentException(token);
                }
                value = body.substring(separator + 1, body.length() - 1);
            } else {
                value = body.substring(separator + 1);
            }
            value = unquote(value).strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException(token);
            }
        }
        Slot slot = Slot.byName(key);
        if (slot != null) {
            if (value == null) {
                throw new IllegalArgumentException(token);
            }
            builder.equipment.put(slot, value.toUpperCase(Locale.ROOT));
            return;
        }
        switch (normalize(key)) {
            case "lifetime" -> builder.lifetimeSeconds = Math.clamp(number(key, value), 0.05, maxLifetime);
            case "health" -> builder.health = Math.clamp(number(key, value), 1, 2048);
            case "name" -> {
                if (value == null) {
                    throw new IllegalArgumentException(token);
                }
                builder.name = value;
            }
            case "hidename" -> builder.hideName = bool(token, value, !negated);
            case "glow" -> builder.glow = bool(token, value, !negated);
            case "silent" -> builder.silent = bool(token, value, !negated);
            case "invulnerable" -> builder.invulnerable = bool(token, value, !negated);
            case "nogravity" -> builder.noGravity = bool(token, value, !negated);
            case "gravity" -> builder.noGravity = !bool(token, value, !negated);
            case "noai" -> builder.noAi = bool(token, value, !negated);
            case "ai" -> builder.noAi = !bool(token, value, !negated);
            case "onfire" -> builder.onFire = bool(token, value, !negated);
            case "charged" -> builder.charged = bool(token, value, !negated);
            case "dinnerbone" -> builder.dinnerbone = bool(token, value, !negated);
            case "jeb" -> builder.jeb = bool(token, value, !negated);
            case "saddle" -> builder.saddle = bool(token, value, !negated);
            case "chest" -> builder.chest = bool(token, value, !negated);
            case "persistent" -> builder.persistent = bool(token, value, !negated);
            case "baby" -> builder.baby = bool(token, value, !negated);
            case "adult" -> builder.baby = !bool(token, value, !negated);
            case "pickup" -> builder.pickup = bool(token, value, !negated);
            case "nopickup" -> builder.pickup = !bool(token, value, !negated);
            case "speed" -> builder.speed = Math.clamp(number(key, value), 0, 5);
            case "scale" -> builder.scale = Math.clamp(number(key, value), 0.1, 10);
            case "target" -> {
                if (value == null) {
                    throw new IllegalArgumentException(token);
                }
                builder.target = value;
            }
            default -> throw new IllegalArgumentException(token);
        }
    }

    /** Maps the accepted aliases to the canonical key. */
    private static String normalize(String key) {
        return switch (key) {
            case "t", "time", "ex", "expire", "expires" -> "lifetime";
            case "hp", "maxhp", "maxhealth" -> "health";
            case "customname", "nametag" -> "name";
            case "namenotvisible" -> "hidename";
            case "glowing" -> "glow";
            case "immortal", "god", "godmode" -> "invulnerable";
            case "gravity" -> "gravity";
            case "ai" -> "ai";
            case "fire" -> "onfire";
            case "powered" -> "charged";
            case "pickupitems", "canpickup" -> "pickup";
            case "nocanpickup" -> "nopickup";
            default -> key;
        };
    }

    private static int firstSeparator(String body) {
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ':' || c == '=' || c == '-' || c == '{') {
                return i;
            }
        }
        return -1;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                || value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static double number(String key, @Nullable String value) {
        if (value == null) {
            throw new IllegalArgumentException(key);
        }
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException(key);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key, e);
        }
    }

    /** Boolean value: bare key means the given default, an explicit value overrides it. */
    private static boolean bool(String token, @Nullable String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw new IllegalArgumentException(token);
        };
    }

    private static final class Builder {
        double lifetimeSeconds = NO_LIFETIME;
        double health = NO_HEALTH;
        @Nullable String name;
        boolean hideName;
        boolean glow;
        boolean silent;
        boolean invulnerable;
        boolean noGravity;
        boolean noAi;
        boolean onFire;
        boolean charged;
        boolean dinnerbone;
        boolean jeb;
        boolean saddle;
        boolean chest;
        @Nullable Boolean baby;
        @Nullable Boolean pickup;
        double speed = NO_SPEED;
        double scale = NO_SCALE;
        @Nullable String target;
        final EnumMap<Slot, String> equipment = new EnumMap<>(Slot.class);
        boolean persistent;

        SpawnOptions build() {
            return new SpawnOptions(lifetimeSeconds, health, name, hideName, glow, silent, invulnerable,
                    noGravity, noAi, onFire, charged, dinnerbone, jeb, saddle, chest, baby, pickup, speed,
                    scale, target, equipment, persistent);
        }
    }
}
