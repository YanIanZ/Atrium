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
            int ignored, int mail, boolean jailed, boolean muted, boolean banned, String stateDetail) {
        boolean hasTimestamps() {
            return login != null || logout != null || lastLogin != null;
        }
    }

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
                mailCount(root), truthy(root, "jailed"), muted(root), banned(root), stateDetail(root));
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
