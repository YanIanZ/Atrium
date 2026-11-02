package dev.yanianz.atrium.module.rtp;

import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * Pure random-point sampling for /rtp. Nothing here touches the world, so it is unit tested.
 *
 * <ul>
 *   <li>{@link Shape#CIRCLE}: a ring between {@code min} and {@code max}, uniform by area:
 *       {@code r = sqrt(u * (max² - min²) + min²)} (a plain {@code min + u * (max - min)} would crowd the
 *       centre, and {@code min + (max - min) * sqrt(u)} crowds the outer edge when min &gt; 0).</li>
 *   <li>{@link Shape#SQUARE}: the square ring {@code min <= max(|dx|, |dz|) <= max}, uniform by area
 *       (picked as one of four rectangles weighted by area, so it is not just the four corner squares).</li>
 *   <li>The world border is respected: points are kept inside {@code border centre ± size / 2} minus a
 *       safety margin. When the ring hardly overlaps the border, the point falls back to a uniform point
 *       of the overlap of the border and the {@code max} square, so a search never targets outside it.</li>
 * </ul>
 */
final class Sampler {
    /** Blocks kept free between a sampled column and the world border. */
    static final int BORDER_MARGIN = 2;
    /** Rejection attempts against the border before falling back. */
    private static final int BORDER_TRIES = 32;

    enum Shape { CIRCLE, SQUARE }

    /** Square world border: centre and half the side length. */
    record Border(double centerX, double centerZ, double halfSize) {
        static Border of(double centerX, double centerZ, double size) {
            return new Border(centerX, centerZ, size / 2.0);
        }

        double minX() {
            return centerX - halfSize + BORDER_MARGIN;
        }

        double maxX() {
            return centerX + halfSize - BORDER_MARGIN;
        }

        double minZ() {
            return centerZ - halfSize + BORDER_MARGIN;
        }

        double maxZ() {
            return centerZ + halfSize - BORDER_MARGIN;
        }

        /** Whether the block column (bx, bz) lies fully inside the border, with the margin. */
        boolean containsBlock(int bx, int bz) {
            return bx >= minX() && bx + 1 <= maxX() && bz >= minZ() && bz + 1 <= maxZ();
        }
    }

    /**
     * @param border null = no border
     */
    record Area(double centerX, double centerZ, int min, int max, Shape shape, @Nullable Border border) {
        Area {
            min = Math.max(0, min);
            max = Math.max(min + 1, max);
            if (shape == null) {
                shape = Shape.CIRCLE;
            }
        }
    }

    /** A block column. */
    record Point(int x, int z) {}

    private Sampler() {}

    static Point sample(Area area, RandomGenerator random) {
        Border border = area.border();
        for (int i = 0; i < BORDER_TRIES; i++) {
            Point p = raw(area, random);
            if (border == null || border.containsBlock(p.x(), p.z())) {
                return p;
            }
        }
        return fallback(area, border, random);
    }

    /** A point of the shape, ignoring the border. */
    static Point raw(Area area, RandomGenerator random) {
        double dx;
        double dz;
        if (area.shape() == Shape.CIRCLE) {
            double min = area.min();
            double max = area.max();
            double r = Math.sqrt(random.nextDouble() * (max * max - min * min) + min * min);
            double theta = random.nextDouble() * 2.0 * Math.PI;
            dx = r * Math.cos(theta);
            dz = r * Math.sin(theta);
        } else {
            double min = area.min();
            double max = area.max();
            // Square ring = top + bottom strips (2max wide, max-min tall) + left + right strips (max-min wide, 2min tall).
            double horizontal = 2 * max * (max - min);
            double vertical = (max - min) * 2 * min;
            double pick = random.nextDouble() * (2 * horizontal + 2 * vertical);
            double along;
            double across = min + random.nextDouble() * (max - min);
            if (pick < 2 * horizontal) {
                along = -max + random.nextDouble() * 2 * max;
                dx = along;
                dz = pick < horizontal ? across : -across;
            } else {
                along = -min + random.nextDouble() * 2 * min;
                dz = along;
                dx = pick - 2 * horizontal < vertical ? across : -across;
            }
        }
        return new Point((int) Math.floor(area.centerX() + dx), (int) Math.floor(area.centerZ() + dz));
    }

    /** Uniform point of (border ∩ max-square); the border alone when they do not overlap. */
    private static Point fallback(Area area, Border border, RandomGenerator random) {
        double minX = Math.max(border.minX(), area.centerX() - area.max());
        double maxX = Math.min(border.maxX(), area.centerX() + area.max());
        double minZ = Math.max(border.minZ(), area.centerZ() - area.max());
        double maxZ = Math.min(border.maxZ(), area.centerZ() + area.max());
        if (maxX - minX < 1 || maxZ - minZ < 1) {
            minX = border.minX();
            maxX = border.maxX();
            minZ = border.minZ();
            maxZ = border.maxZ();
        }
        if (maxX - minX < 1 || maxZ - minZ < 1) {
            // Border smaller than the margin: its centre is the only sensible column.
            return new Point((int) Math.floor(border.centerX()), (int) Math.floor(border.centerZ()));
        }
        // Block columns [lo, hi - 1] lie fully inside [lo, hi].
        int loX = (int) Math.ceil(minX);
        int hiX = (int) Math.floor(maxX) - 1;
        int loZ = (int) Math.ceil(minZ);
        int hiZ = (int) Math.floor(maxZ) - 1;
        if (hiX < loX || hiZ < loZ) {
            return new Point((int) Math.floor(border.centerX()), (int) Math.floor(border.centerZ()));
        }
        return new Point(loX + random.nextInt(hiX - loX + 1), loZ + random.nextInt(hiZ - loZ + 1));
    }
}
