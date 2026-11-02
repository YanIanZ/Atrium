package dev.yanianz.atrium.module.importer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.spongepowered.configurate.ConfigurationNode;

/**
 * Parses one {@code plugins/Essentials/userdata/<uuid>.yml} file. Pure: it reads YAML nodes and returns
 * data, it never resolves worlds or touches a service, so fixture files can test every rule.
 *
 * <p>Unknown keys (homes' materials, kit timers, ...) are ignored, and a malformed value becomes a
 * {@code problem} on that item instead of failing the whole player.
 */
final class EssentialsUserParser {
    /** Same rule as the homes module; an imported name must survive /home and /delhome. */
    private static final Pattern HOME_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final BigDecimal MAX_MONEY = new BigDecimal("1000000000000000");

    private EssentialsUserParser() {}

    /** One home whose world is still a raw token (UUID or name). */
    record RawHome(String name, EssentialsLocations.EssLocation location, String problem) {
        boolean ok() {
            return problem == null;
        }
    }

    record UserFile(String id, UUID uuid, String label, String nickname, BigDecimal money, String moneyProblem,
            List<RawHome> homes, EssentialsLocations.EssLocation lastLocation,
            EssentialsLocations.EssLocation logoutLocation, Long login, Long logout, Long lastLogin,
            int ignored, int mail, boolean jailed, boolean muted, boolean banned, String stateDetail,
            States states, List<ParsedMail> mails) {
        boolean hasTimestamps() {
            return login != null || logout != null || lastLogin != null;
        }
    }

    /**
     * Punishment details. Timestamps are epoch millis; {@code 0} (or absent) means "no end", i.e. permanent.
     */
    record States(boolean muted, long muteUntil, String muteReason, boolean jailed, String jail, long jailUntil,
            boolean banned, long banUntil, String banReason) {}

    /** One mail message. {@code created == 0} when the format has no time; {@code expires == 0} = never. */
    record ParsedMail(String senderName, UUID sender, String message, long created, long expires, boolean read) {}

    /** Strips {@code .yml} from a userdata file name. */
    static String fileId(String fileName) {
        String name = fileName == null ? "" : fileName;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yml")) {
            return name.substring(0, name.length() - 4);
        }
        if (lower.endsWith(".yaml")) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }

    /** The UUID encoded in a userdata file name, or {@code null} for a legacy name-based file. */
    static UUID uuidFromId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(id.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static UserFile parse(String fileName, ConfigurationNode root) {
        String id = fileId(fileName);
        UUID uuid = uuidFromId(id);
        String name = text(root, "lastAccountName", "lastKnownName", "name");
        String label = name == null || name.isBlank() ? id : name;

        String nickname = rawText(root, "nickname");

        BigDecimal money = null;
        String moneyProblem = null;
        ConfigurationNode moneyNode = child(root, "money", "balance");
        if (moneyNode != null && !moneyNode.virtual()) {
            String raw = moneyNode.getString();
            Optional<BigDecimal> parsed = EssentialsMoney.parse(raw);
            if (parsed.isPresent()) {
                money = parsed.get();
            } else {
                moneyProblem = raw == null || raw.isBlank() ? "(empty)" : raw.trim();
            }
        }

        return new UserFile(id, uuid, label, nickname, money, moneyProblem, homes(root), lastLocation(root),
                logoutLocation(root), timestamp(root, "login"), timestamp(root, "logout"),
                timestamp(root, "lastlogin", "lastLogin", "lastlogout", "lastLogout"), ignoreCount(root),
                mailCount(root), truthy(root, "jailed"), muted(root), banned(root), stateDetail(root),
                states(root), mails(root));
    }

    // ---- homes ----

    private static List<RawHome> homes(ConfigurationNode root) {
        ConfigurationNode homes = child(root, "homes");
        if (homes == null || homes.virtual()) {
            return List.of();
        }
        if (!homes.isMap()) {
            return List.of(new RawHome(null, null, "homes is not a map"));
        }
        List<RawHome> out = new ArrayList<>();
        for (var entry : homes.childrenMap().entrySet()) {
            String name = String.valueOf(entry.getKey()).trim();
            if (!HOME_NAME.matcher(name).matches()) {
                out.add(new RawHome(null, null, "invalid home name '" + name + "'"));
                continue;
            }
            Optional<EssentialsLocations.EssLocation> location = EssentialsLocations.parse(entry.getValue());
            out.add(location.map(value -> new RawHome(name, value, null))
                    .orElseGet(() -> new RawHome(name, null, "invalid location for home '" + name + "'")));
        }
        return List.copyOf(out);
    }

    // ---- locations ----

    private static EssentialsLocations.EssLocation lastLocation(ConfigurationNode root) {
        ConfigurationNode teleport = child(root, "teleport");
        if (teleport != null && !teleport.virtual()) {
            Optional<EssentialsLocations.EssLocation> parsed =
                    EssentialsLocations.parse(child(teleport, "lastLocation", "last-location", "last"));
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        return EssentialsLocations.parse(child(root, "lastLocation", "last-location")).orElse(null);
    }

    private static EssentialsLocations.EssLocation logoutLocation(ConfigurationNode root) {
        ConfigurationNode teleport = child(root, "teleport");
        if (teleport != null && !teleport.virtual()) {
            Optional<EssentialsLocations.EssLocation> parsed =
                    EssentialsLocations.parse(child(teleport, "logoutLocation", "logout-location", "logout"));
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }
        return EssentialsLocations.parse(child(root, "logoutLocation", "logout-location")).orElse(null);
    }

    private static Long timestamp(ConfigurationNode root, String... names) {
        ConfigurationNode timestamps = child(root, "timestamps", "timestamps-v2");
        Long value = readLong(child(timestamps, names));
        if (value != null) {
            return value;
        }
        return readLong(child(root, names));
    }

    private static Long readLong(ConfigurationNode node) {
        if (node == null || node.virtual()) {
            return null;
        }
        try {
            long value = node.getLong(0L);
            return value > 0L ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- report-only data ----

    private static int ignoreCount(ConfigurationNode root) {
        ConfigurationNode ignore = child(root, "ignore", "ignored");
        return listSize(ignore);
    }

    private static int mailCount(ConfigurationNode root) {
        ConfigurationNode mail = child(root, "mail", "mails");
        return listSize(mail);
    }

    private static int listSize(ConfigurationNode node) {
        if (node == null || node.virtual()) {
            return 0;
        }
        if (node.isList()) {
            return node.childrenList().size();
        }
        return node.getString() == null || node.getString().isBlank() ? 0 : 1;
    }

    private static States states(ConfigurationNode root) {
        ConfigurationNode timestamps = child(root, "timestamps", "timestamps-v2");
        long muteUntil = orZero(readLong(child(timestamps, "mute", "mute-timeout")));
        if (muteUntil == 0L) {
            muteUntil = orZero(readLong(child(root, "mute-timeout", "muteTimeout", "mutetimeout")));
        }
        long jailUntil = orZero(readLong(child(timestamps, "jail", "jail-timeout")));
        if (jailUntil == 0L) {
            jailUntil = orZero(readLong(child(root, "jail-timeout", "jailTimeout", "jailtimeout")));
        }
        ConfigurationNode ban = child(root, "ban");
        long banUntil = ban.isMap() ? orZero(readLong(child(ban, "timeout", "expires"))) : 0L;
        String banReason = ban.isMap() ? text(ban, "reason", "Reason") : text(root, "banReason", "ban-reason");
        String jail = text(root, "jail", "jail-name", "jailName");
        if (jail != null && (jail.equalsIgnoreCase("true") || jail.equalsIgnoreCase("false"))) {
            jail = null;
        }
        return new States(muted(root), muteUntil, text(root, "mute-reason", "muteReason", "mutereason"),
                truthy(root, "jailed"), jail, jailUntil, banned(root), banUntil, banReason);
    }

    private static long orZero(Long value) {
        return value == null ? 0L : value;
    }

    private static List<ParsedMail> mails(ConfigurationNode root) {
        ConfigurationNode mail = child(root, "mail", "mails");
        if (mail == null || mail.virtual()) {
            return List.of();
        }
        List<ConfigurationNode> entries = mail.isList() ? new ArrayList<ConfigurationNode>(mail.childrenList())
                : mail.isMap() ? new ArrayList<ConfigurationNode>(mail.childrenMap().values()) : List.of(mail);
        List<ParsedMail> out = new ArrayList<>();
        for (ConfigurationNode entry : entries) {
            ParsedMail parsed = entry.isMap() ? mailFromMap(entry) : mailFromText(entry.getString());
            if (parsed != null && !parsed.message().isBlank()) {
                out.add(parsed);
            }
        }
        return List.copyOf(out);
    }

    private static ParsedMail mailFromMap(ConfigurationNode node) {
        String message = text(node, "message", "msg", "text");
        if (message == null) {
            return null;
        }
        String sender = text(node, "sender-name", "senderName", "sender_name", "sender");
        UUID senderId = uuidFromId(text(node, "sender-uuid", "senderUUID", "senderUuid", "sender_uuid"));
        return new ParsedMail(sender == null || sender.isBlank() ? "Unknown" : sender, senderId, message,
                orZero(readLong(child(node, "timestamp", "time-sent", "timeSent", "sent"))),
                orZero(readLong(child(node, "expire", "time-expire", "timeExpire", "expires"))),
                child(node, "read").getBoolean(false));
    }

    /**
     * A JSON object written by EssentialsX 2.19+ ({@code {"senderName":...,"message":...,"timestamp":...}}),
     * or a legacy plain line {@code Sender: text}.
     */
    static ParsedMail mailFromText(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.startsWith("{")) {
            try {
                com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
                String message = jsonText(json, "message", "msg");
                if (message == null) {
                    return null;
                }
                String sender = jsonText(json, "senderName", "sender-name", "sender");
                return new ParsedMail(sender == null || sender.isBlank() ? "Unknown" : sender,
                        uuidFromId(jsonText(json, "senderUUID", "senderUuid", "sender-uuid")), message,
                        jsonLong(json, "timestamp", "timeSent"), jsonLong(json, "expire", "timeExpire"),
                        json.has("read") && json.get("read").isJsonPrimitive() && json.get("read").getAsBoolean());
            } catch (RuntimeException e) {
                return new ParsedMail("Unknown", null, text, 0L, 0L, false);
            }
        }
        int colon = text.indexOf(": ");
        if (colon > 0 && colon <= 32 && text.substring(0, colon).matches("[A-Za-z0-9_ ]+")) {
            return new ParsedMail(text.substring(0, colon).strip(), null, text.substring(colon + 2), 0L, 0L, false);
        }
        return new ParsedMail("Unknown", null, text, 0L, 0L, false);
    }

    private static String jsonText(com.google.gson.JsonObject json, String... keys) {
        for (String key : keys) {
            var value = json.get(key);
            if (value != null && value.isJsonPrimitive()) {
                return value.getAsString();
            }
        }
        return null;
    }

    private static long jsonLong(com.google.gson.JsonObject json, String... keys) {
        for (String key : keys) {
            var value = json.get(key);
            if (value != null && value.isJsonPrimitive()) {
                try {
                    return Math.max(0L, value.getAsLong());
                } catch (RuntimeException e) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private static boolean banned(ConfigurationNode root) {
        ConfigurationNode banned = child(root, "banned");
        if (!banned.virtual()) {
            return banned.getBoolean(false);
        }
        ConfigurationNode ban = child(root, "ban");
        if (ban.virtual()) {
            return false;
        }
        if (ban.isMap()) {
            return !ban.childrenMap().isEmpty();
        }
        if (ban.isList()) {
            return !ban.childrenList().isEmpty();
        }
        return ban.getBoolean(false);
    }

    private static boolean muted(ConfigurationNode root) {
        ConfigurationNode muted = child(root, "muted", "mute");
        if (muted == null || muted.virtual()) {
            return false;
        }
        if (muted.isMap() || muted.isList()) {
            return true;
        }
        String text = muted.getString();
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.trim().toLowerCase(Locale.ROOT);
        return !lower.equals("false") && !lower.equals("0") && !lower.equals("no");
    }

    private static String stateDetail(ConfigurationNode root) {
        ConfigurationNode ban = child(root, "ban");
        String reason = ban.isMap() ? text(ban, "reason", "Reason") : null;
        if (reason == null) {
            reason = text(root, "banReason", "ban-reason", "mutedReason", "muted-reason", "jailReason");
        }
        return reason;
    }

    private static boolean truthy(ConfigurationNode root, String... names) {
        ConfigurationNode node = child(root, names);
        if (node == null || node.virtual()) {
            return false;
        }
        if (node.isMap()) {
            return !node.childrenMap().isEmpty();
        }
        if (node.isList()) {
            return !node.childrenList().isEmpty();
        }
        return node.getBoolean(false);
    }

    // ---- node helpers ----

    private static ConfigurationNode child(ConfigurationNode node, String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            ConfigurationNode direct = node.node(name);
            if (!direct.virtual()) {
                return direct;
            }
        }
        for (var entry : node.childrenMap().entrySet()) {
            String key = String.valueOf(entry.getKey());
            for (String name : names) {
                if (key.equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
        }
        return node.node(names[0]);
    }

    private static String text(ConfigurationNode node, String... names) {
        ConfigurationNode value = child(node, names);
        return value == null || value.virtual() ? null : value.getString();
    }

    private static String rawText(ConfigurationNode node, String... names) {
        String value = text(node, names);
        return value == null || value.isBlank() ? null : value;
    }
}
