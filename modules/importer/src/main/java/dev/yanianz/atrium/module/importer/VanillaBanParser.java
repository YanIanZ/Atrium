package dev.yanianz.atrium.module.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Parses the server's own ban lists in the server folder: {@code banned-players.json}
 * ({@code [{"uuid","name","created","source","expires","reason"}]}) and {@code banned-ips.json}
 * ({@code [{"ip","created","source","expires","reason"}]}). Dates are {@code yyyy-MM-dd HH:mm:ss Z};
 * {@code expires: "forever"} is a permanent ban. CMI and EssentialsX both keep their bans here.
 */
final class VanillaBanParser {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT);

    private VanillaBanParser() {}

    /**
     * @param uuid    banned account, or {@code null} for an IP ban
     * @param created {@code null} when the entry has no readable creation date
     * @param expires {@code null} for permanent
     */
    record Ban(UUID uuid, String name, String ip, String source, String reason, Instant created, Instant expires,
            String problem) {
        boolean ok() {
            return problem == null;
        }

        String label() {
            return uuid == null ? ip : (name == null ? uuid.toString() : name + " (" + uuid + ")");
        }
    }

    static List<Ban> parsePlayers(String json) {
        return parse(json, false);
    }

    static List<Ban> parseIps(String json) {
        return parse(json, true);
    }

    private static List<Ban> parse(String json, boolean ip) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonArray()) {
            throw new IllegalArgumentException("not a JSON array");
        }
        JsonArray array = root.getAsJsonArray();
        List<Ban> bans = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                bans.add(new Ban(null, null, null, null, null, null, null, "entry is not an object"));
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String name = text(entry, "name");
            String address = text(entry, "ip");
            UUID uuid = null;
            if (!ip) {
                uuid = CmiWarpFileParser.uuid(text(entry, "uuid"));
                if (uuid == null) {
                    bans.add(new Ban(null, name, null, null, null, null, null, "missing or invalid uuid"));
                    continue;
                }
            } else if (address == null || address.isBlank()) {
                bans.add(new Ban(null, null, null, null, null, null, null, "missing ip"));
                continue;
            }
            Instant created = date(text(entry, "created"));
            String rawExpires = text(entry, "expires");
            Instant expires = null;
            if (rawExpires != null && !rawExpires.isBlank() && !rawExpires.equalsIgnoreCase("forever")) {
                expires = date(rawExpires);
                if (expires == null) {
                    bans.add(new Ban(uuid, name, address, null, null, null, null, "unreadable expiry '" + rawExpires + "'"));
                    continue;
                }
            }
            bans.add(new Ban(uuid, name, ip ? address.trim() : null, text(entry, "source"), text(entry, "reason"),
                    created, expires, null));
        }
        return List.copyOf(bans);
    }

    static Instant date(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.trim(), DATE).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }
}
