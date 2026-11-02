package dev.yanianz.atrium.module.seating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeatRulesTest {
    private static final SeatRules.Allowed ALL = new SeatRules.Allowed(true, true, true);
    private static final SeatRules.Allowed SLABS_ONLY = new SeatRules.Allowed(false, true, false);

    @Test
    void nonSeatBlocksAreRejected() {
        assertEquals(SeatRules.Verdict.NOT_A_SEAT,
                SeatRules.check(null, false, false, true, ALL));
    }

    @Test
    void doubleSlabIsAFullBlockNotASeat() {
        assertEquals(SeatRules.Verdict.NOT_A_SEAT,
                SeatRules.check(SeatRules.Kind.SLAB, false, true, true, ALL));
    }

    @Test
    void topHalvesAreRejected() {
        assertEquals(SeatRules.Verdict.TOP_HALF,
                SeatRules.check(SeatRules.Kind.SLAB, true, false, true, ALL));
        assertEquals(SeatRules.Verdict.TOP_HALF,
                SeatRules.check(SeatRules.Kind.STAIRS, true, false, true, ALL));
    }

    @Test
    void carpetsHaveNoHalves() {
        assertEquals(SeatRules.Verdict.OK,
                SeatRules.check(SeatRules.Kind.CARPET, false, false, true, ALL));
    }

    @Test
    void disabledMaterialsAreRejected() {
        assertEquals(SeatRules.Verdict.DISABLED,
                SeatRules.check(SeatRules.Kind.STAIRS, false, false, true, SLABS_ONLY));
        assertEquals(SeatRules.Verdict.DISABLED,
                SeatRules.check(SeatRules.Kind.CARPET, false, false, true, SLABS_ONLY));
        assertEquals(SeatRules.Verdict.OK,
                SeatRules.check(SeatRules.Kind.SLAB, false, false, true, SLABS_ONLY));
    }

    @Test
    void headroomIsRequired() {
        assertEquals(SeatRules.Verdict.NO_HEADROOM,
                SeatRules.check(SeatRules.Kind.STAIRS, false, false, false, ALL));
    }

    @Test
    void flatSurfacesUseTheirTopHeight() {
        assertEquals(new SeatRules.Offset(0.5, 0.5, 0.5),
                SeatRules.offset(SeatRules.Kind.SLAB, SeatRules.Facing.SOUTH, SeatRules.Shape.STRAIGHT));
        assertEquals(new SeatRules.Offset(0.5, 1.0 / 16.0, 0.5),
                SeatRules.offset(SeatRules.Kind.CARPET, SeatRules.Facing.NORTH, SeatRules.Shape.STRAIGHT));
    }

    @Test
    void straightStairsSeatTowardsTheLowSide() {
        assertEquals(new SeatRules.Offset(0.25, 0.5, 0.5),
                SeatRules.offset(SeatRules.Kind.STAIRS, SeatRules.Facing.EAST, SeatRules.Shape.STRAIGHT));
        assertEquals(new SeatRules.Offset(0.75, 0.5, 0.5),
                SeatRules.offset(SeatRules.Kind.STAIRS, SeatRules.Facing.WEST, SeatRules.Shape.STRAIGHT));
        assertEquals(new SeatRules.Offset(0.5, 0.5, 0.75),
                SeatRules.offset(SeatRules.Kind.STAIRS, SeatRules.Facing.NORTH, SeatRules.Shape.STRAIGHT));
        assertEquals(new SeatRules.Offset(0.5, 0.5, 0.25),
                SeatRules.offset(SeatRules.Kind.STAIRS, SeatRules.Facing.SOUTH, SeatRules.Shape.STRAIGHT));
    }

    @Test
    void cornerStairsShiftSideways() {
        assertEquals(new SeatRules.Offset(0.25, 0.5, 0.2),
                SeatRules.offset(SeatRules.Kind.STAIRS, SeatRules.Facing.EAST, SeatRules.Shape.INNER_LEFT));
        assertEquals(new SeatRules.Offset(0.25, 0.5, 0.8),
                SeatRules.offset(SeatRules.Kind.STAIRS, SeatRules.Facing.EAST, SeatRules.Shape.INNER_RIGHT));
        assertEquals(new SeatRules.Offset(0.8, 0.5, 0.25),
                SeatRules.offset(SeatRules.Kind.STAIRS, SeatRules.Facing.SOUTH, SeatRules.Shape.OUTER_LEFT));
        assertEquals(new SeatRules.Offset(0.8, 0.5, 0.75),
                SeatRules.offset(SeatRules.Kind.STAIRS, SeatRules.Facing.NORTH, SeatRules.Shape.OUTER_RIGHT));
    }

    @Test
    void everyOffsetStaysInsideTheBlock() {
        for (SeatRules.Facing facing : SeatRules.Facing.values()) {
            for (SeatRules.Shape shape : SeatRules.Shape.values()) {
                SeatRules.Offset offset = SeatRules.offset(SeatRules.Kind.STAIRS, facing, shape);
                assertTrue(offset.x() >= 0.1 && offset.x() <= 0.9, facing + "/" + shape + " x");
                assertTrue(offset.z() >= 0.1 && offset.z() <= 0.9, facing + "/" + shape + " z");
                assertEquals(0.5, offset.y());
            }
        }
    }
}
