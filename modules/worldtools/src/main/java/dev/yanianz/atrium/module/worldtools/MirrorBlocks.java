package dev.yanianz.atrium.module.worldtools;

import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;

/**
 * Applies a {@link MirrorTransform} to block data with Bukkit's own rotation and mirror operations, so
 * stairs, chests, logs, trapdoors and every other directional block keep pointing the mirrored way.
 *
 * <p>Runs wherever the data is read/used: the result is a fresh copy, never the source block's data.
 */
final class MirrorBlocks {
    private MirrorBlocks() {}

    static BlockData transform(BlockData source, MirrorTransform transform) {
        BlockData data = source.clone();
        for (MirrorTransform.Step step : transform.steps()) {
            switch (step) {
                case ROTATE_90 -> data.rotate(StructureRotation.CLOCKWISE_90);
                case ROTATE_180 -> data.rotate(StructureRotation.CLOCKWISE_180);
                case ROTATE_270 -> data.rotate(StructureRotation.COUNTERCLOCKWISE_90);
                case MIRROR_X -> data.mirror(Mirror.LEFT_RIGHT);
                case MIRROR_Z -> data.mirror(Mirror.FRONT_BACK);
            }
        }
        return data;
    }
}
