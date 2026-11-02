package dev.yanianz.atrium.module.seating;

import org.jspecify.annotations.Nullable;

/**
 * Pure seating geometry and eligibility. This class never touches Bukkit, so the rules that decide
 * whether a block is a chair and where the seat entity belongs are unit-tested without a server.
 */
final class SeatRules {
    enum Kind {
        STAIRS, SLAB, CARPET
    }

    enum Facing {
        NORTH, EAST, SOUTH, WEST
    }

    enum Shape {
        STRAIGHT, INNER_LEFT, INNER_RIGHT, OUTER_LEFT, OUTER_RIGHT
    }

    /** Why a block cannot be used as a seat. */
    enum Verdict {
        OK, NOT_A_SEAT, TOP_HALF, DISABLED, NO_HEADROOM
    }

    /** Which seat materials the administrator enabled. */
    record Allowed(boolean stairs, boolean slabs, boolean carpets) {
        boolean allows(Kind kind) {
            return switch (kind) {
                case STAIRS -> stairs;
                case SLAB -> slabs;
                case CARPET -> carpets;
            };
        }
    }

    /** Offset from the block's minimum corner to the seat position. */
    record Offset(double x, double y, double z) {}

    /** Distance the seat moves sideways on a corner stair. */
    static final double STAIR_SIDE_SHIFT = 0.3;
    private static final double MIN = 0.1;
    private static final double MAX = 0.9;

    private SeatRules() {}

    /**
     * Decides whether a block may be sat on.
     *
     * @param kind       the seat material, or {@code null} when the block is not a seat at all
     * @param topHalf    true for the top half of a stair or slab
     * @param doubleSlab true for a double slab (a full block, not a seat)
     * @param headroom   true when the block above the seat is passable
     */
    static Verdict check(@Nullable Kind kind, boolean topHalf, boolean doubleSlab, boolean headroom,
            Allowed allowed) {
        if (kind == null) {
            return Verdict.NOT_A_SEAT;
        }
        if (kind == Kind.SLAB && doubleSlab) {
            return Verdict.NOT_A_SEAT;
        }
        if (kind != Kind.CARPET && topHalf) {
            // Sitting on the thin top half leaves the player floating in the block above.
            return Verdict.TOP_HALF;
        }
        if (!allowed.allows(kind)) {
            return Verdict.DISABLED;
        }
        if (!headroom) {
            return Verdict.NO_HEADROOM;
        }
        return Verdict.OK;
    }

    /**
     * Seat position inside the block. Slabs and stairs use their top surface; a carpet uses its
     * 1/16 block height. Stairs move the seat towards the low side and corner stairs shift it
     * sideways so the player does not hang over the edge.
     */
    static Offset offset(Kind kind, Facing facing, Shape shape) {
        return switch (kind) {
            case CARPET -> new Offset(0.5, 1.0 / 16.0, 0.5);
            case SLAB -> new Offset(0.5, 0.5, 0.5);
            case STAIRS -> stairs(facing, shape);
        };
    }

    private static Offset stairs(Facing facing, Shape shape) {
        double x = 0.5;
        double z = 0.5;
        switch (facing) {
            case NORTH -> z = 0.75;
            case SOUTH -> z = 0.25;
            case EAST -> x = 0.25;
            case WEST -> x = 0.75;
        }
        // Left/right is relative to the direction the stairs face; bend corner stairs out of the
        // middle so the player sits on the step instead of the edge.
        double side = switch (shape) {
            case INNER_LEFT, OUTER_LEFT -> STAIR_SIDE_SHIFT;
            case INNER_RIGHT, OUTER_RIGHT -> -STAIR_SIDE_SHIFT;
            case STRAIGHT -> 0.0;
        };
        switch (facing) {
            case EAST -> z -= side;
            case WEST -> z += side;
            case SOUTH -> x += side;
            case NORTH -> x -= side;
        }
        return new Offset(clamp(x), 0.5, clamp(z));
    }

    private static double clamp(double value) {
        return Math.clamp(value, MIN, MAX);
    }
}
