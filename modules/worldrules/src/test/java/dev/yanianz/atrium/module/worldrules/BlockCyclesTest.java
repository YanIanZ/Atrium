package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.junit.jupiter.api.Test;

class BlockCyclesTest {
    @Test
    void stairShapesCycleThroughEveryShape() {
        assertEquals(Stairs.Shape.INNER_LEFT, BlockCycles.nextShape(Stairs.Shape.STRAIGHT));
        assertEquals(Stairs.Shape.INNER_RIGHT, BlockCycles.nextShape(Stairs.Shape.INNER_LEFT));
        assertEquals(Stairs.Shape.OUTER_LEFT, BlockCycles.nextShape(Stairs.Shape.INNER_RIGHT));
        assertEquals(Stairs.Shape.OUTER_RIGHT, BlockCycles.nextShape(Stairs.Shape.OUTER_LEFT));
        assertEquals(Stairs.Shape.STRAIGHT, BlockCycles.nextShape(Stairs.Shape.OUTER_RIGHT));
    }

    @Test
    void slabTypesOnlySwapBetweenBottomAndTop() {
        assertEquals(Slab.Type.TOP, BlockCycles.nextSlab(Slab.Type.BOTTOM));
        assertEquals(Slab.Type.BOTTOM, BlockCycles.nextSlab(Slab.Type.TOP));
        // A double slab must never be produced: one slab would break into two items.
        assertEquals(Slab.Type.DOUBLE, BlockCycles.nextSlab(Slab.Type.DOUBLE));
    }

    @Test
    void axesOnlyUseSupportedValues() {
        assertEquals(Axis.Z, BlockCycles.nextAxis(Axis.X, Set.of(Axis.X, Axis.Z)));
        assertEquals(Axis.X, BlockCycles.nextAxis(Axis.Z, Set.of(Axis.X, Axis.Z)));
        assertEquals(Axis.Y, BlockCycles.nextAxis(Axis.X, Set.of(Axis.Y)));
    }

    @Test
    void facingUsesTheSupportedFaceSetAndWrapsSafely() {
        assertEquals(BlockFace.EAST, BlockCycles.nextFacing(BlockFace.NORTH, Set.of(BlockFace.NORTH, BlockFace.EAST)));
        assertEquals(BlockFace.NORTH, BlockCycles.nextFacing(BlockFace.SOUTH, Set.of(BlockFace.NORTH, BlockFace.EAST)));
        assertEquals(BlockFace.UP, BlockCycles.nextFacing(BlockFace.DOWN, Set.of(BlockFace.DOWN, BlockFace.UP)));
    }

    @Test
    void rotationFollowsTheSixteenValidRotations() {
        assertEquals(BlockFace.NORTH_NORTH_EAST, BlockCycles.nextRotation(BlockFace.NORTH));
        assertEquals(BlockFace.NORTH, BlockCycles.nextRotation(BlockFace.NORTH_NORTH_WEST));
        assertEquals(BlockFace.NORTH, BlockCycles.nextRotation(BlockFace.SELF));
    }

    @Test
    void maskCyclesWrapDeterministically() {
        assertEquals(1, BlockCycles.nextMask(0, 4));
        assertEquals(0, BlockCycles.nextMask(15, 4));
        assertEquals(0, BlockCycles.nextMask(7, 0));
    }
}
