package dev.yanianz.atrium.module.homes;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * All homes of one player, keyed by lower-case name and sorted. Immutable: {@link #with} and
 * {@link #without} return new instances, so it can be swapped atomically as a user component.
 */
public record Homes(SortedMap<String, Home> byName) {
    public static final Homes EMPTY = new Homes(new TreeMap<>());

    /** Allowed home names: letters, digits, '_' and '-', 1 to 32 characters. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    public Homes {
        byName = Collections.unmodifiableSortedMap(new TreeMap<>(byName));
    }

    public static Homes of(Collection<Home> homes) {
        TreeMap<String, Home> map = new TreeMap<>();
        for (Home home : homes) {
            map.put(key(home.name()), home);
        }
        return new Homes(map);
    }

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Optional<Home> get(String name) {
        return Optional.ofNullable(byName.get(key(name)));
    }

    public boolean contains(String name) {
        return byName.containsKey(key(name));
    }

    public int count() {
        return byName.size();
    }

    public List<Home> list() {
        return List.copyOf(byName.values());
    }

    /** Adds or replaces (case-insensitive) a home. */
    public Homes with(Home home) {
        TreeMap<String, Home> copy = new TreeMap<>(byName);
        copy.put(key(home.name()), home);
        return new Homes(copy);
    }

    public Homes without(String name) {
        if (!contains(name)) {
            return this;
        }
        TreeMap<String, Home> copy = new TreeMap<>(byName);
        copy.remove(key(name));
        return new Homes(copy);
    }

    /**
     * Whether a player with {@code limit} homes may save {@code name}. Replacing an existing home is
     * always allowed; every stored home counts, including homes in worlds that are not loaded.
     */
    public boolean canSet(String name, int limit) {
        return contains(name) || count() < limit;
    }

    /**
     * World-aware limit. The global limit counts every home; a non-null {@code worldLimit} additionally
     * caps the homes stored in {@code world}. Replacing a home inside the same world never needs a free
     * slot, while moving a home into another world does.
     */
    public boolean canSet(String name, String world, int globalLimit, @org.jspecify.annotations.Nullable Integer worldLimit) {
        boolean existing = contains(name);
        boolean sameWorld = existing && get(name)
                .map(home -> world != null && world.equalsIgnoreCase(home.location().world()))
                .orElse(false);
        if (!existing && count() >= globalLimit) {
            return false;
        }
        if (sameWorld) {
            return true;
        }
        return worldLimit == null || countIn(world) < worldLimit;
    }

    /** Homes whose stored world matches {@code world} (case-insensitive). */
    public int countIn(String world) {
        if (world == null) {
            return 0;
        }
        return (int) byName.values().stream()
                .filter(home -> world.equalsIgnoreCase(home.location().world()))
                .count();
    }

    Map<String, Home> asMap() {
        return byName;
    }
}
