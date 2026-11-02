package dev.yanianz.atrium.module.portals;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** An admin's in-memory wand selection (two block corners). Immutable; cleared when the admin quits. */
record Selection(@Nullable Corner pos1, @Nullable Corner pos2) {
    static final Selection EMPTY = new Selection(null, null);

    record Corner(String world, int x, int y, int z) {}

    Selection withPos1(Corner corner) {
        return new Selection(corner, pos2);
    }

    Selection withPos2(Corner corner) {
        return new Selection(pos1, corner);
    }

    /** The box, when both corners are set and in the same world. */
    Optional<Cuboid> area() {
        if (pos1 == null || pos2 == null || !pos1.world().equals(pos2.world())) {
            return Optional.empty();
        }
        return Optional.of(Cuboid.of(pos1.world(), pos1.x(), pos1.y(), pos1.z(), pos2.x(), pos2.y(), pos2.z()));
    }
}
