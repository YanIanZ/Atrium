package dev.yanianz.atrium.api.statistics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Statistic;

/**
 * Stable names and units for the Bukkit statistics, shared by the {@code inspect} and {@code placeholders}
 * modules so a scoreboard and the {@code /stats} menu always agree on a key.
 *
 * <p>Keys are the lower-case enum name, e.g. {@code walk_one_cm}, without a prefix. A placeholder or command
 * may also write {@code stat_walk_one_cm} or {@code statistic_walk_one_cm}. The catalog is built from
 * {@link Statistic#values()} instead of a fixed list, so a server update that adds a statistic exposes it
 * automatically and an older server never references a constant that does not exist.
 *
 * <p>Subtyped statistics ({@code mine_block}, {@code break_item}, {@code kill_entity}, ...) need a material
 * or entity argument. The catalog marks them with {@link Entry#subtyped()}; a total is not stored by Bukkit
 * and must be summed by the caller (the inspect menu and the optional placeholder totals do that).
 */
public final class StatisticsCatalog {
    /** What a raw value means, so every reader formats it the same way. */
    public enum Kind {
        /** A plain counter. */
        COUNT,
        /** A duration in ticks (displayed as a duration, edited in seconds). */
        TIME,
        /** A distance in centimetres (displayed in metres or kilometres, edited in metres). */
        DISTANCE
    }

    /** Broad groups used by the menu; purely presentational. */
    public enum Category {
        MOVEMENT,
        COMBAT,
        WORLD,
        INTERACTION,
        ITEMS,
        OTHER
    }

    public record Entry(Statistic statistic, String key, Category category, Kind kind, boolean subtyped) {}

    private static final Set<String> TIME = Set.of(
            "PLAY_ONE_MINUTE", "SNEAK_TIME", "TIME_SINCE_DEATH", "TIME_SINCE_REST", "TOTAL_WORLD_TIME");

    private static final Set<String> MOVEMENT = Set.of(
            "AVIATE_ONE_CM", "BOAT_ONE_CM", "CLIMB_ONE_CM", "CROUCH_ONE_CM", "FALL_ONE_CM", "FLY_ONE_CM",
            "HAPPY_GHAST_ONE_CM", "HORSE_ONE_CM", "JUMP", "MINECART_ONE_CM", "NAUTILUS_ONE_CM", "PIG_ONE_CM",
            "SPRINT_ONE_CM", "STRIDER_ONE_CM", "SWIM_ONE_CM", "WALK_ONE_CM", "WALK_ON_WATER_ONE_CM",
            "WALK_UNDER_WATER_ONE_CM", "PLAY_ONE_MINUTE", "LEAVE_GAME");

    private static final Set<String> COMBAT = Set.of(
            "DAMAGE_ABSORBED", "DAMAGE_BLOCKED_BY_SHIELD", "DAMAGE_DEALT", "DAMAGE_DEALT_ABSORBED",
            "DAMAGE_DEALT_RESISTED", "DAMAGE_RESISTED", "DAMAGE_TAKEN", "DEATHS", "MOB_KILLS", "PLAYER_KILLS",
            "TARGET_HIT", "KILL_ENTITY", "ENTITY_KILLED_BY");

    private static final Set<String> ITEMS = Set.of(
            "BREAK_ITEM", "CRAFT_ITEM", "DROP", "PICKUP", "USE_ITEM", "ITEM_ENCHANTED", "CLEAN_SHULKER_BOX");

    private static final Set<String> WORLD = Set.of(
            "MINE_BLOCK", "TOTAL_WORLD_TIME", "TIME_SINCE_REST", "SLEEP_IN_BED", "RAID_TRIGGER", "RAID_WIN");

    private static final List<Entry> ENTRIES;
    private static final Map<String, Entry> BY_KEY;

    static {
        List<Entry> entries = new ArrayList<>();
        Map<String, Entry> byKey = new LinkedHashMap<>();
        for (Statistic statistic : Statistic.values()) {
            String key = statistic.name().toLowerCase(Locale.ROOT);
            Entry entry = new Entry(statistic, key, categoryOf(statistic), kindOf(statistic),
                    statistic.getType() != Statistic.Type.UNTYPED);
            entries.add(entry);
            byKey.put(key, entry);
        }
        ENTRIES = List.copyOf(entries);
        BY_KEY = Map.copyOf(byKey);
    }

    private StatisticsCatalog() {}

    /** Every statistic known to the running server, in enum order. */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    public static Optional<Entry> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String normalized = normalize(key);
        Entry found = BY_KEY.get(normalized);
        if (found != null) {
            return Optional.of(found);
        }
        // A statistic added by a plugin or a newer server can still be addressed by its enum name.
        try {
            return Optional.of(entryOf(Statistic.valueOf(normalized.toUpperCase(Locale.ROOT))));
        } catch (IllegalArgumentException | NoSuchFieldError e) {
            return Optional.empty();
        }
    }

    /** The catalog key of a statistic, never {@code null}. */
    public static String keyOf(Statistic statistic) {
        return statistic.name().toLowerCase(Locale.ROOT);
    }

    public static Entry entryOf(Statistic statistic) {
        Entry known = BY_KEY.get(keyOf(statistic));
        if (known != null) {
            return known;
        }
        return new Entry(statistic, keyOf(statistic), categoryOf(statistic), kindOf(statistic),
                statistic.getType() != Statistic.Type.UNTYPED);
    }

    /** Accepts {@code stat_}, {@code statistic_} and {@code atrium_stat_} forms, case-insensitively. */
    public static String normalize(String key) {
        String normalized = key.strip().toLowerCase(Locale.ROOT);
        for (String prefix : List.of("atrium_statistic_", "atrium_stat_", "statistic_", "stat_")) {
            if (normalized.startsWith(prefix)) {
                return normalized.substring(prefix.length());
            }
        }
        return normalized;
    }

    public static boolean isTime(Statistic statistic) {
        return TIME.contains(statistic.name());
    }

    public static boolean isDistance(Statistic statistic) {
        return statistic.name().endsWith("_ONE_CM");
    }

    private static Category categoryOf(Statistic statistic) {
        String name = statistic.name();
        if (MOVEMENT.contains(name)) {
            return Category.MOVEMENT;
        }
        if (COMBAT.contains(name)) {
            return Category.COMBAT;
        }
        if (ITEMS.contains(name)) {
            return Category.ITEMS;
        }
        if (WORLD.contains(name)) {
            return Category.WORLD;
        }
        if (name.startsWith("INTERACT_WITH") || name.endsWith("_INSPECTED") || name.endsWith("_OPENED")
                || name.endsWith("_INTERACTION") || name.endsWith("_PLAYED")) {
            return Category.INTERACTION;
        }
        return Category.OTHER;
    }

    private static Kind kindOf(Statistic statistic) {
        if (isTime(statistic)) {
            return Kind.TIME;
        }
        return isDistance(statistic) ? Kind.DISTANCE : Kind.COUNT;
    }

    /**
     * Human-readable value: a duration for time statistics, metres or kilometres for distance statistics,
     * and the plain number otherwise. Never locale-dependent.
     */
    public static String format(Entry entry, long raw) {
        return switch (entry.kind()) {
            case TIME -> formatDuration(Math.max(0L, raw / 20L));
            case DISTANCE -> formatDistance(Math.max(0L, raw));
            case COUNT -> Long.toString(raw);
        };
    }

    /** Ticks per second trusted by Bukkit statistics. */
    public static long ticksPerSecond() {
        return 20L;
    }

    /** Centimetres per metre in a distance statistic. */
    public static long centimetresPerMetre() {
        return 100L;
    }

    static String formatDistance(long centimetres) {
        double metres = centimetres / 100.0;
        if (metres < 1000.0) {
            return String.format(Locale.ROOT, "%.1f m", metres);
        }
        return String.format(Locale.ROOT, "%.2f km", metres / 1000.0);
    }

    static String formatDuration(long seconds) {
        if (seconds < 60) {
            return seconds + "s";
        }
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append('d');
        }
        if (hours > 0) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(hours).append('h');
        }
        if (minutes > 0 && days == 0) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(minutes).append('m');
        }
        return out.toString();
    }
}
