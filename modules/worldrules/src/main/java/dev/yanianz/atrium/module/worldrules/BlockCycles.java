package dev.yanianz.atrium.module.worldrules;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Axis;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Rotatable;
import org.bukkit.block.data.type.Gate;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;

/**
 * Deterministic state cycling for {@code /blockcycle}; every branch only uses values the state supports.
 *
 * <p>Only properties that never change the block's drops are touched: shape, slab half, open, facing,
 * axis, rotation and fence/wall connections. Growth state ({@code Ageable}), bites, honey, egg/pickle/
 * candle counts and every other inventory-like state are deliberately left alone, so cycling can never
 * duplicate or destroy an item.
 */
final class BlockCycles {
    private static final List<BlockFace> FACING_ORDER = List.of(
            BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN);

    private static final List<BlockFace> ROTATION_ORDER = List.of(
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST,
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST);

    private static final List<Stairs.Shape> STAIR_SHAPES = List.of(
            Stairs.Shape.STRAIGHT, Stairs.Shape.INNER_LEFT, Stairs.Shape.INNER_RIGHT,
            Stairs.Shape.OUTER_LEFT, Stairs.Shape.OUTER_RIGHT);

    private BlockCycles() {}

    /** Returns the next state, or {@code null} when this block type has no supported cycle. */
    static BlockData next(BlockData data) {
        if (data == null) {
            return null;
        }
        if (data instanceof Stairs stairs) {
            BlockData copy = data.clone();
            ((Stairs) copy).setShape(nextShape(stairs.getShape()));
            return copy;
        }
        if (data instanceof Slab slab) {
            BlockData copy = data.clone();
            ((Slab) copy).setType(nextSlab(slab.getType()));
            return copy;
        }
        if (data instanceof Gate gate) {
            BlockData copy = data.clone();
            ((Gate) copy).setInWall(!gate.isInWall());
            return copy;
        }
        if (data instanceof Openable openable) {
            BlockData copy = data.clone();
            ((Openable) copy).setOpen(!openable.isOpen());
            return copy;
        }
        if (data instanceof Orientable orientable) {
            BlockData copy = data.clone();
            ((Orientable) copy).setAxis(nextAxis(orientable.getAxis(), orientable.getAxes()));
            return copy;
        }
        if (data instanceof Rotatable rotatable) {
            BlockData copy = data.clone();
            ((Rotatable) copy).setRotation(nextRotation(rotatable.getRotation()));
            return copy;
        }
        if (data instanceof MultipleFacing facing) {
            BlockData copy = data.clone();
            cycleFaces((MultipleFacing) copy, facing);
            return copy;
        }
        if (data instanceof Directional directional) {
            BlockData copy = data.clone();
            ((Directional) copy).setFacing(nextFacing(directional.getFacing(), directional.getFaces()));
            return copy;
        }
        // Ageable (crops, cocoa, chorus flowers, ...) and every other state changes drops or growth.
        return null;
    }

    private static void cycleFaces(MultipleFacing copy, MultipleFacing original) {
        List<BlockFace> allowed = new ArrayList<>();
        for (BlockFace face : FACING_ORDER) {
            if (original.getAllowedFaces().contains(face)) {
                allowed.add(face);
            }
        }
        if (allowed.isEmpty()) {
            return;
        }
        int mask = 0;
        for (int i = 0; i < allowed.size(); i++) {
            if (original.hasFace(allowed.get(i))) {
                mask |= 1 << i;
            }
        }
        int next = nextMask(mask, allowed.size());
        for (int i = 0; i < allowed.size(); i++) {
            copy.setFace(allowed.get(i), (next & (1 << i)) != 0);
        }
    }

    static Stairs.Shape nextShape(Stairs.Shape current) {
        int index = STAIR_SHAPES.indexOf(current);
        return STAIR_SHAPES.get((index + 1) % STAIR_SHAPES.size());
    }

    /** Bottom and top only: turning a slab into a double slab would drop two items from one. */
    static Slab.Type nextSlab(Slab.Type current) {
        return switch (current) {
            case BOTTOM -> Slab.Type.TOP;
            case TOP -> Slab.Type.BOTTOM;
            case DOUBLE -> Slab.Type.DOUBLE;
        };
    }

    /** Cycles X -> Y -> Z among the axes this block actually supports. */
    static Axis nextAxis(Axis current, Set<Axis> allowed) {
        List<Axis> axes = new ArrayList<>(3);
        for (Axis axis : List.of(Axis.X, Axis.Y, Axis.Z)) {
            if (allowed.contains(axis)) {
                axes.add(axis);
            }
        }
        if (axes.isEmpty()) {
            return current;
        }
        int index = axes.indexOf(current);
        return axes.get((index + 1) % axes.size());
    }

    /** Cycles the cardinal/vertical faces this block supports, never an unsupported face. */
    static BlockFace nextFacing(BlockFace current, Set<BlockFace> allowed) {
        List<BlockFace> faces = new ArrayList<>(6);
        for (BlockFace face : FACING_ORDER) {
            if (allowed.contains(face)) {
                faces.add(face);
            }
        }
        if (faces.isEmpty()) {
            return current;
        }
        int index = faces.indexOf(current);
        return faces.get((index + 1) % faces.size());
    }

    static BlockFace nextRotation(BlockFace current) {
        int index = ROTATION_ORDER.indexOf(current);
        return ROTATION_ORDER.get((index + 1) % ROTATION_ORDER.size());
    }

    /** Deterministic bit-mask cycle used for fences and walls. */
    static int nextMask(int current, int bits) {
        if (bits <= 0) {
            return 0;
        }
        int bounded = Math.min(bits, 30);
        int max = (1 << bounded) - 1;
        return (current + 1) & max;
    }
}
