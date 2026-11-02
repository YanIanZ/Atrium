package dev.yanianz.atrium.module.importer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * Parses CMI locations. CMI stores {@code world:x:y:z:pitch:yaw} and escapes separators inside the world
 * name as {@code T8C} ({@code :}), {@code T7C} ({@code ;}) and {@code T9C} ({@code %}).
 *
 * <p>The reported pitch/yaw order is ambiguous across CMI versions, so both are validated: a value outside
 * the legal pitch range is treated as yaw and swapped when the other value is a legal pitch. Coordinates
 * that are not finite are rejected.
 */
final class CmiLocations {
    private CmiLocations() {}

    static Optional<StoredLocation> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String text = raw.trim();
        if (text.startsWith("{")) {
            return parseJson(text);
        }
        if (text.indexOf(';') >= 0) {
            Optional<StoredLocation> semicolon = StoredLocation.decode(text);
            if (semicolon.isPresent()) {
                return semicolon;
            }
        }
        String[] parts = text.split(":", -1);
        if (parts.length < 6) {
            return Optional.empty();
        }
        int numbersFrom = parts.length - 5;
        StringJoiner world = new StringJoiner(":");
        for (int i = 0; i < numbersFrom; i++) {
            world.add(unescape(parts[i]));
        }
        try {
            double x = Double.parseDouble(parts[numbersFrom]);
            double y = Double.parseDouble(parts[numbersFrom + 1]);
            double z = Double.parseDouble(parts[numbersFrom + 2]);
            float fourth = Float.parseFloat(parts[numbersFrom + 3]);
            float fifth = Float.parseFloat(parts[numbersFrom + 4]);
            float pitch = fourth;
            float yaw = fifth;
            if (!Float.isFinite(pitch) || pitch < -90f || pitch > 90f) {
                if (Float.isFinite(fifth) && fifth >= -90f && fifth <= 90f) {
                    pitch = fifth;
                    yaw = fourth;
                } else {
                    return Optional.empty();
                }
            }
            return Optional.of(new StoredLocation(world.toString(), x, y, z, yaw, pitch));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<StoredLocation> parseJson(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (!element.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject object = element.getAsJsonObject();
            String world = string(object, "world");
            if (world == null) {
                world = string(object, "World");
            }
            if (world == null || world.isBlank()) {
                return Optional.empty();
            }
            Double x = number(object, "x");
            Double y = number(object, "y");
            Double z = number(object, "z");
            Double yaw = number(object, "yaw");
            Double pitch = number(object, "pitch");
            if (x == null || y == null || z == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(new StoredLocation(world, x, y, z,
                        yaw == null ? 0f : yaw.floatValue(), pitch == null ? 0f : pitch.floatValue()));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static String unescape(String value) {
        return value.replace("T9C", "%").replace("T8C", ":").replace("T7C", ";");
    }

    static String escape(String value) {
        return value.replace("%", "T9C").replace(":", "T8C").replace(";", "T7C");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private static Double number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
