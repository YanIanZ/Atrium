package dev.yanianz.atrium.module.portals;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which portals each player is standing in (the "inside" flag). A portal only triggers when the
 * player was not inside it before the move, so standing or walking around inside a portal never triggers
 * it again. After a portal teleport the player is marked inside every portal at the destination, which
 * stops two portals that point at each other from sending the player back and forth.
 *
 * <p>Each player's entry is only written from that player's own thread; the map itself is concurrent.
 */
final class EntryTracker {
    private final Map<UUID, Set<String>> inside = new ConcurrentHashMap<>();

    /**
     * Records a move. {@code touched} are the portals the movement passed through (including the end point),
     * {@code nowInside} the portals containing the end point. Returns the portal keys the player entered with
     * this move, in the order of {@code touched}.
     */
    List<String> move(UUID player, Collection<String> touched, Collection<String> nowInside) {
        Set<String> before = inside.getOrDefault(player, Set.of());
        Set<String> entered = new LinkedHashSet<>();
        for (String key : touched) {
            if (!before.contains(key)) {
                entered.add(key);
            }
        }
        set(player, nowInside);
        return List.copyOf(entered);
    }

    /** Replaces the inside set without triggering anything (teleports, joins, portal destinations). */
    void set(UUID player, Collection<String> nowInside) {
        if (nowInside.isEmpty()) {
            inside.remove(player);
        } else {
            inside.put(player, Set.copyOf(nowInside));
        }
    }

    Set<String> inside(UUID player) {
        return inside.getOrDefault(player, Set.of());
    }

    boolean isInsideAny(UUID player) {
        return inside.containsKey(player);
    }

    void clear(UUID player) {
        inside.remove(player);
    }

    void clearAll() {
        inside.clear();
    }
}
