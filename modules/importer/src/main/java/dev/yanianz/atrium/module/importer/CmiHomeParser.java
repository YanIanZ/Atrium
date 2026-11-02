package dev.yanianz.atrium.module.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the CMI {@code Homes} column. Two formats exist:
 * <ul>
 *   <li>legacy: {@code name$-p$-f$-material%%world:x:y:z:pitch:yaw} entries separated by {@code ;}</li>
 *   <li>JSON: {@code {"name":["world:x:y:z:pitch:yaw","b"|"f"|"p"|slot|material]}}</li>
 * </ul>
 */
final class CmiHomeParser {
    /** Same rule as the homes module; an imported name must survive /home and /delhome. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private CmiHomeParser() {}

    record ParsedHome(String name, StoredLocation location, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    static List<ParsedHome> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String text = raw.trim();
        if (text.startsWith("{") || text.startsWith("[")) {
            return parseJson(text);
        }
        return parseLegacy(text);
    }

    private static List<ParsedHome> parseLegacy(String text) {
        List<ParsedHome> homes = new ArrayList<>();
        for (String entry : text.split(";")) {
            if (entry.isBlank()) {
                continue;
            }
            int split = entry.indexOf("%%");
            if (split < 0) {
                homes.add(new ParsedHome(null, null, "home entry has no location separator"));
                continue;
            }
            String key = entry.substring(0, split);
            String locationText = entry.substring(split + 2);
            String name = key.split("\\$-", -1)[0];
            name = CmiLocations.unescape(name == null ? "" : name).trim();
            if (!NAME.matcher(name).matches()) {
                homes.add(new ParsedHome(null, null, "invalid home name '" + name + "'"));
                continue;
            }
            StoredLocation location = CmiLocations.parse(locationText).orElse(null);
            homes.add(location == null
                    ? new ParsedHome(name, null, "invalid location for home '" + name + "'")
                    : new ParsedHome(name, location, null));
        }
        return homes;
    }

    private static List<ParsedHome> parseJson(String text) {
        List<ParsedHome> homes = new ArrayList<>();
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element.isJsonObject()) {
                for (var entry : element.getAsJsonObject().entrySet()) {
                    homes.add(fromJson(entry.getKey(), entry.getValue()));
                }
            } else if (element.isJsonArray()) {
                for (JsonElement item : element.getAsJsonArray()) {
                    if (!item.isJsonObject()) {
                        homes.add(new ParsedHome(null, null, "home JSON array entry is not an object"));
                        continue;
                    }
                    JsonObject object = item.getAsJsonObject();
                    String name = firstString(object, "name", "Name");
                    JsonElement value = object.has("loc") ? object.get("loc")
                            : object.has("Loc") ? object.get("Loc") : object.get("location");
                    homes.add(fromJson(name, value));
                }
            } else {
                homes.add(new ParsedHome(null, null, "home JSON is not an object or array"));
            }
        } catch (RuntimeException e) {
            homes.add(new ParsedHome(null, null, "invalid home JSON: " + e.getMessage()));
        }
        return homes;
    }

    private static ParsedHome fromJson(String name, JsonElement value) {
        if (name == null || !NAME.matcher(name).matches()) {
            return new ParsedHome(null, null, "invalid home name in JSON");
        }
        String locationText = null;
        if (value != null && value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            if (!array.isEmpty() && array.get(0).isJsonPrimitive()) {
                locationText = array.get(0).getAsString();
            }
        } else if (value != null && value.isJsonPrimitive()) {
            locationText = value.getAsString();
        } else if (value != null && value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            locationText = firstString(object, "loc", "Loc", "location", "Location");
        }
        if (locationText == null) {
            return new ParsedHome(name, null, "home '" + name + "' has no location in JSON");
        }
        StoredLocation location = CmiLocations.parse(locationText).orElse(null);
        return location == null
                ? new ParsedHome(name, null, "invalid location for home '" + name + "'")
                : new ParsedHome(name, location, null);
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString();
            }
        }
        return null;
    }
}
