package dev.yanianz.atrium.module.economy;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Maps worlds to economy world groups. Pure logic, no server access, so it is unit tested directly.
 *
 * <p>Balances are stored under a group <em>key</em>. The default group is always stored under
 * {@link #DEFAULT_KEY}, whatever its configured display name, so renaming {@code default-group} or turning
 * groups on later never moves existing money: every balance written before groups existed is in the
 * default group. Other groups are stored under their lower-case configured name.
 *
 * <p>Resolution order for a world: an exact (case-insensitive) world name in any group wins, then the first
 * group (in configuration order) with a matching glob pattern ({@code *} and {@code ?}), then the default
 * group. When groups are disabled every world resolves to the default group.
 */
final class WorldGroups {
    /** Storage key of the default group. Every balance from before world groups lives here. */
    static final String DEFAULT_KEY = "default";
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private final boolean enabled;
    private final String defaultName;
    private final boolean allowCrossGroupPay;
    /** Configured non-default groups by key, in configuration order. Values are the raw patterns. */
    private final Map<String, List<String>> patterns;
    private final Map<String, String> exact = new HashMap<>();
    private final List<Map.Entry<Pattern, String>> globs = new ArrayList<>();
    private final Map<String, String> resolved = new ConcurrentHashMap<>();

    WorldGroups(boolean enabled, String defaultGroup, Map<String, List<String>> groups, boolean allowCrossGroupPay) {
        this.enabled = enabled;
        this.allowCrossGroupPay = allowCrossGroupPay;
        String defaultName = normalize(defaultGroup == null || defaultGroup.isBlank() ? DEFAULT_KEY : defaultGroup);
        if (!NAME.matcher(defaultName).matches()) {
            throw new IllegalArgumentException("Invalid economy default-group '" + defaultGroup + "': use 1-32 of a-z 0-9 _ -");
        }
        this.defaultName = defaultName;
        Map<String, List<String>> configured = new LinkedHashMap<>();
        if (groups != null) {
            for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                String name = normalize(entry.getKey());
                if (!NAME.matcher(name).matches()) {
                    throw new IllegalArgumentException("Invalid economy world group name '" + entry.getKey()
                            + "': use 1-32 of a-z 0-9 _ -");
                }
                String key = name.equals(defaultName) ? DEFAULT_KEY : name;
                if (key.equals(DEFAULT_KEY) && !name.equals(defaultName)) {
                    throw new IllegalArgumentException("The economy world group name 'default' is reserved for the default"
                            + " group; rename it or set default-group: default");
                }
                if (configured.containsKey(key)) {
                    throw new IllegalArgumentException("Duplicate economy world group '" + name + "'");
                }
                List<String> worlds = new ArrayList<>();
                for (String world : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
                    if (world == null || world.isBlank()) continue;
                    String pattern = world.trim();
                    worlds.add(pattern);
                    String lower = pattern.toLowerCase(Locale.ROOT);
                    if (pattern.contains("*") || pattern.contains("?")) {
                        globs.add(Map.entry(glob(lower), key));
                    } else {
                        String previous = exact.putIfAbsent(lower, key);
                        if (previous != null && !previous.equals(key)) {
                            throw new IllegalArgumentException("World '" + pattern + "' is listed in economy world groups '"
                                    + display(previous) + "' and '" + name + "'");
                        }
                    }
                }
                configured.put(key, List.copyOf(worlds));
            }
        }
        configured.putIfAbsent(DEFAULT_KEY, List.of());
        this.patterns = Collections.unmodifiableMap(configured);
    }

    static WorldGroups from(EconomyConfig.WorldGroups config) {
        EconomyConfig.WorldGroups section = config == null ? new EconomyConfig.WorldGroups() : config;
        return new WorldGroups(section.enabled, section.defaultGroup, section.worldGroups, section.allowCrossGroupPay);
    }

    /** Groups disabled: exactly one (default) group, every world maps to it. */
    static WorldGroups disabled() {
        return new WorldGroups(false, DEFAULT_KEY, Map.of(), false);
    }

    boolean enabled() { return enabled; }

    boolean allowCrossGroupPay() { return !enabled || allowCrossGroupPay; }

    /** Whether money may move from one group key to another (a payment or a cheque). */
    boolean permits(String from, String to) {
        return from.equals(to) || allowCrossGroupPay();
    }

    /**
     * Group a payment receiver is credited in: the explicitly chosen group, else the group the receiver is in
     * while online ({@code receiverOnlineGroup}, null when offline), else the payer's group.
     */
    static String receiverGroup(String payerGroup, String explicit, String receiverOnlineGroup) {
        if (explicit != null) return explicit;
        return receiverOnlineGroup != null ? receiverOnlineGroup : payerGroup;
    }

    /** Display name of the default group. */
    String defaultName() { return defaultName; }

    /** Storage key of the group a world belongs to. */
    String keyOfWorld(String world) {
        if (!enabled || world == null) return DEFAULT_KEY;
        String lower = world.toLowerCase(Locale.ROOT);
        return resolved.computeIfAbsent(lower, this::resolve);
    }

    private String resolve(String lower) {
        String key = exact.get(lower);
        if (key != null) return key;
        for (Map.Entry<Pattern, String> glob : globs) {
            if (glob.getKey().matcher(lower).matches()) return glob.getValue();
        }
        return DEFAULT_KEY;
    }

    /**
     * Storage key for a group name typed by an administrator or passed through the API, or {@code null} when
     * no such group is configured. The default group answers to its display name and to {@code default}.
     * With groups disabled only the default group exists.
     */
    String keyOf(String name) {
        if (name == null) return null;
        String lower = normalize(name);
        if (lower.equals(defaultName) || lower.equals(DEFAULT_KEY)) return DEFAULT_KEY;
        return enabled && patterns.containsKey(lower) ? lower : null;
    }

    /** Display name of a storage key. */
    String display(String key) {
        return DEFAULT_KEY.equals(key) ? defaultName : key;
    }

    /** All configured group keys, default first. With groups disabled only the default key. */
    List<String> keys() {
        List<String> keys = new ArrayList<>();
        keys.add(DEFAULT_KEY);
        if (enabled) {
            for (String key : patterns.keySet()) if (!key.equals(DEFAULT_KEY)) keys.add(key);
        }
        return List.copyOf(keys);
    }

    /** Display names, default first. */
    List<String> names() {
        return keys().stream().map(this::display).toList();
    }

    /** Configured world names and patterns of a group key (empty for the default group unless listed). */
    List<String> worlds(String key) {
        return patterns.getOrDefault(key, List.of());
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            switch (ch) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
