package dev.yanianz.atrium.module.tablist;

import dev.yanianz.atrium.api.tablist.TabListService.Placement;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.jspecify.annotations.Nullable;

/**
 * Tags other modules add to list names through {@link dev.yanianz.atrium.api.tablist.TabListService}, for
 * example {@code [AFK]}. Thread-safe: tags are written from any thread and read on the player's thread while
 * rendering. The tablist module composes them into the one name it renders, so no module sets a list name itself
 * and nothing is drawn twice, whichever engine shows the list.
 */
final class NameTags {
    record Tag(Placement placement, Component text) {}

    /** Per player: source id → tag, ordered by source id so the composed name is stable. */
    private final Map<UUID, NavigableMap<String, Tag>> tags = new ConcurrentHashMap<>();

    /**
     * Sets or (with a {@code null} or empty tag) removes the tag of one source.
     *
     * @return whether the stored tags changed
     */
    boolean set(UUID player, String source, Placement placement, @Nullable Component tag) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(placement, "placement");
        boolean[] changed = {false};
        if (tag == null || tag.equals(Component.empty()) || isBlankText(tag)) {
            tags.computeIfPresent(player, (id, map) -> {
                changed[0] = map.remove(source) != null;
                return map.isEmpty() ? null : map;
            });
            return changed[0];
        }
        Tag next = new Tag(placement, tag);
        tags.compute(player, (id, map) -> {
            NavigableMap<String, Tag> current = map == null ? new ConcurrentSkipListMap<>() : map;
            changed[0] = !next.equals(current.put(source, next));
            return current;
        });
        return changed[0];
    }

    /** Removes every tag of one source; returns the players whose name changed. */
    Set<UUID> clear(String source) {
        Set<UUID> affected = new HashSet<>();
        for (UUID player : Set.copyOf(tags.keySet())) {
            tags.computeIfPresent(player, (id, map) -> {
                if (map.remove(source) != null) {
                    affected.add(id);
                }
                return map.isEmpty() ? null : map;
            });
        }
        return affected;
    }

    void forget(UUID player) {
        tags.remove(player);
    }

    void clearAll() {
        tags.clear();
    }

    /** The rendered list name with this player's tags around it; the same instance when there are none. */
    Component compose(UUID player, Component name) {
        NavigableMap<String, Tag> mine = tags.get(player);
        if (mine == null || mine.isEmpty()) {
            return name;
        }
        TextComponent.Builder builder = Component.text();
        for (Tag tag : mine.values()) {
            if (tag.placement() == Placement.BEFORE) {
                builder.append(tag.text());
            }
        }
        builder.append(name);
        for (Tag tag : mine.values()) {
            if (tag.placement() == Placement.AFTER) {
                builder.append(tag.text());
            }
        }
        return builder.build();
    }

    private static boolean isBlankText(Component component) {
        return component instanceof TextComponent text && text.content().isEmpty() && text.children().isEmpty();
    }
}
