package dev.yanianz.atrium.module.worldtools;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Per-player selection corners. Kept in memory only: a selection is a short-lived editing aid and must never
 * survive a restart (a stale cuboid would let an admin edit a region they no longer mean).
 */
final class Selections {
    private final Map<UUID, Cuboid.Point> first = new ConcurrentHashMap<>();
    private final Map<UUID, Cuboid.Point> second = new ConcurrentHashMap<>();

    /** Sets corner 1 or 2; a corner of another world replaces both corners instead of mixing worlds. */
    void setCorner(UUID player, int corner, Cuboid.Point point) {
        Map<UUID, Cuboid.Point> own = corner == 1 ? first : second;
        Map<UUID, Cuboid.Point> other = corner == 1 ? second : first;
        Cuboid.Point existingOther = other.get(player);
        if (existingOther != null && !Cuboid.sameWorld(existingOther, point)) {
            other.remove(player);
        }
        own.put(player, point);
    }

    Optional<Cuboid> selection(UUID player) {
        Cuboid.Point a = first.get(player);
        Cuboid.Point b = second.get(player);
        if (a == null || b == null || !Cuboid.sameWorld(a, b)) {
            return Optional.empty();
        }
        return Optional.of(Cuboid.of(a, b));
    }

    /** The corner that is set, for "/select info" when only one exists. */
    Optional<Cuboid.Point> corner(UUID player, int corner) {
        return Optional.ofNullable((corner == 1 ? first : second).get(player));
    }

    boolean hasBoth(UUID player) {
        return first.containsKey(player) && second.containsKey(player);
    }

    void clear(UUID player) {
        first.remove(player);
        second.remove(player);
    }

    void setSelection(UUID player, @Nullable Cuboid cuboid) {
        if (cuboid == null) {
            clear(player);
            return;
        }
        first.put(player, new Cuboid.Point(cuboid.world(), cuboid.minX(), cuboid.minY(), cuboid.minZ()));
        second.put(player, new Cuboid.Point(cuboid.world(), cuboid.maxX(), cuboid.maxY(), cuboid.maxZ()));
    }

    void forget(UUID player) {
        clear(player);
    }
}
