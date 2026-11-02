package dev.yanianz.atrium.module.holograms;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable hologram definition. There is always at least one page (it may be empty). Edits create a new
 * value; the manager swaps it in only after it was stored.
 */
record Hologram(String name, StoredLocation location, HologramSettings settings, List<List<HoloLine>> pages,
        List<ClickAction> actions, long createdAt) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");

    Hologram {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(settings, "settings");
        pages = pages == null || pages.isEmpty() ? List.of(List.of()) : pages.stream().map(List::copyOf).toList();
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    String key() {
        return key(name);
    }

    int lineCount() {
        return LineIndex.count(pages);
    }

    Hologram withLocation(StoredLocation newLocation) {
        return new Hologram(name, newLocation, settings, pages, actions, createdAt);
    }

    Hologram withSettings(HologramSettings newSettings) {
        return new Hologram(name, location, newSettings, pages, actions, createdAt);
    }

    Hologram withPages(List<List<HoloLine>> newPages) {
        return new Hologram(name, location, settings, newPages, actions, createdAt);
    }

    Hologram withActions(List<ClickAction> newActions) {
        return new Hologram(name, location, settings, pages, newActions, createdAt);
    }

    /** Mutable deep copy of the pages, for edits. */
    List<List<HoloLine>> mutablePages() {
        List<List<HoloLine>> copy = new ArrayList<>();
        for (List<HoloLine> page : pages) {
            copy.add(new ArrayList<>(page));
        }
        return copy;
    }

    /** True when location (world, position or rotation) differs; such a change needs new entities. */
    boolean movedFrom(Hologram other) {
        return !location.equals(other.location);
    }

    /** Raw strings per page, for the public API. */
    List<List<String>> rawPages() {
        return pages.stream().map(p -> p.stream().map(HoloLine::raw).toList()).toList();
    }
}
