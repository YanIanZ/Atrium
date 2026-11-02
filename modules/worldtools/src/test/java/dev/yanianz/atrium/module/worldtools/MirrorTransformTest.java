package dev.yanianz.atrium.module.worldtools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Position math, aliases and the block-data step list of every mirror transform. */
class MirrorTransformTest {
    private static final int[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int[] VERTICAL = {1, 0, 0, 0, -1, 0, 0, 0, 1};

    @Test void atLeastTwelveTransformsAreOffered() {
        assertTrue(MirrorTransform.all().size() >= 12, "must offer at least the 12 CMI transforms");
        assertEquals(MirrorTransform.all().size(), MirrorTransform.names().stream().distinct().count());
    }

    @Test void everyTransformIsASignedPermutationOfTheCube() {
        for (MirrorTransform transform : MirrorTransform.all()) {
            int[] m = transform.matrix();
            for (int row = 0; row < 3; row++) {
                int nonZero = 0;
                for (int col = 0; col < 3; col++) {
                    int value = m[row * 3 + col];
                    if (value != 0) {
                        assertEquals(1, Math.abs(value), transform + " row " + row);
                        nonZero++;
                    }
                }
                assertEquals(1, nonZero, transform + " row " + row);
            }
            for (int col = 0; col < 3; col++) {
                int nonZero = 0;
                for (int row = 0; row < 3; row++) {
                    if (m[row * 3 + col] != 0) {
                        nonZero++;
                    }
                }
                assertEquals(1, nonZero, transform + " column " + col);
            }
            assertEquals(1, Math.abs(transform.determinant()), transform + " determinant");
            assertEquals(transform.determinant() < 0, transform.mirrored(), transform + " mirrored");
        }
    }

    @Test void fourApplicationsReturnToTheStartAndTheOrderIsATMostFour() {
        for (MirrorTransform transform : MirrorTransform.all()) {
            int[] point = {2, 1, 3};
            int[] moved = point;
            int order = 0;
            do {
                moved = transform.apply(moved[0], moved[1], moved[2]);
                order++;
            } while (!Arrays.equals(point, moved) && order <= 8);
            assertTrue(order == 2 || order == 4, transform + " has order " + order);
            int[] four = point;
            for (int i = 0; i < 4; i++) {
                four = transform.apply(four[0], four[1], four[2]);
            }
            assertArrayEquals(point, four, transform + " four applications must be the identity");
        }
    }

    @Test void everyTransformKeepsTheCentreBlockInPlace() {
        for (MirrorTransform transform : MirrorTransform.all()) {
            assertArrayEquals(new int[] {10, 64, -7},
                    transform.applyAt(10, 64, -7, 10, 64, -7), transform.toString());
        }
    }

    @Test void mirrorPositionsAreExact() {
        assertArrayEquals(new int[] {-2, 1, 3}, MirrorTransform.MIRROR_X.apply(2, 1, 3));
        assertArrayEquals(new int[] {2, -1, 3}, MirrorTransform.MIRROR_Y.apply(2, 1, 3));
        assertArrayEquals(new int[] {2, 1, -3}, MirrorTransform.MIRROR_Z.apply(2, 1, 3));
        assertArrayEquals(new int[] {-2, -1, 3}, MirrorTransform.MIRROR_XY.apply(2, 1, 3));
        assertArrayEquals(new int[] {2, -1, -3}, MirrorTransform.MIRROR_YZ.apply(2, 1, 3));
        assertArrayEquals(new int[] {-2, 1, -3}, MirrorTransform.MIRROR_XZ.apply(2, 1, 3));
        assertArrayEquals(new int[] {-2, -1, -3}, MirrorTransform.MIRROR_XYZ.apply(2, 1, 3));
        assertArrayEquals(new int[] {3, 1, 2}, MirrorTransform.DIAGONAL.apply(2, 1, 3));
        assertArrayEquals(new int[] {-3, 1, -2}, MirrorTransform.ANTIDIAGONAL.apply(2, 1, 3));
        assertArrayEquals(new int[] {0, 0, 1}, MirrorTransform.ROTATE_90.apply(1, 0, 0));
        assertArrayEquals(new int[] {0, 0, -1}, MirrorTransform.ROTATE_270.apply(1, 0, 0));
        assertArrayEquals(new int[] {-1, 0, 0}, MirrorTransform.ROTATE_180.apply(1, 0, 0));
        assertArrayEquals(new int[] {-3, -1, 2}, MirrorTransform.ROTATE_90_YFLIP.apply(2, 1, 3));
        assertArrayEquals(new int[] {3, -1, 2}, MirrorTransform.DIAGONAL_YFLIP.apply(2, 1, 3));
    }

    @Test void absolutePositionsAddTheCentreBack() {
        assertArrayEquals(new int[] {8, 64, 13},
                MirrorTransform.MIRROR_X.applyAt(10, 64, 10, 12, 64, 13));
        assertArrayEquals(new int[] {9, 64, 12},
                MirrorTransform.ROTATE_90.applyAt(10, 64, 10, 12, 64, 11));
    }

    @Test void aliasesResolveToTheSameTransform() {
        assertSame(MirrorTransform.MIRROR_X, MirrorTransform.parse("x").orElseThrow());
        assertSame(MirrorTransform.MIRROR_X, MirrorTransform.parse(" MIRROR_X ").orElseThrow());
        assertSame(MirrorTransform.MIRROR_Z, MirrorTransform.parse("zr").orElseThrow());
        assertSame(MirrorTransform.ROTATE_90, MirrorTransform.parse("rot90").orElseThrow());
        assertSame(MirrorTransform.ROTATE_270, MirrorTransform.parse("r270").orElseThrow());
        assertSame(MirrorTransform.DIAGONAL, MirrorTransform.parse("diag").orElseThrow());
        assertSame(MirrorTransform.ANTIDIAGONAL, MirrorTransform.parse("anti").orElseThrow());
        assertSame(MirrorTransform.ROTATE_90_YFLIP, MirrorTransform.parse("rot90y").orElseThrow());
        assertTrue(MirrorTransform.parse("").isEmpty());
        assertTrue(MirrorTransform.parse("   ").isEmpty());
        assertTrue(MirrorTransform.parse("nope").isEmpty());
        assertTrue(MirrorTransform.parse("rot90+nope").isEmpty());
    }

    @Test void combinationsApplyLeftToRight() {
        // rotate then mirror x: (x, z) -> (z, x) = mirror across x = z.
        assertEquals(MirrorTransform.DIAGONAL, MirrorTransform.parse("rot90+mirror_x").orElseThrow());
        // mirror x then rotate: (x, z) -> (-z, -x) = the other diagonal.
        assertEquals(MirrorTransform.ANTIDIAGONAL, MirrorTransform.parse("mirror_x+rot90").orElseThrow());
        // Two 90 degree rotations are 180 degrees.
        assertEquals(MirrorTransform.ROTATE_180, MirrorTransform.parse("rot90+rot90").orElseThrow());
        // Mirroring twice is the identity, however it was written.
        MirrorTransform twice = MirrorTransform.parse("mirror_x+mirror_x").orElseThrow();
        assertArrayEquals(new int[] {2, 1, 3}, twice.apply(2, 1, 3));
        assertEquals(MirrorTransform.parse("rot90+rot270").orElseThrow(), twice);
        // Whitespace around the parts is ignored.
        assertEquals(MirrorTransform.ROTATE_180, MirrorTransform.parse(" rot90 + rot90 ").orElseThrow());
        // A vertical flip in the middle stays a vertical flip.
        MirrorTransform flip = MirrorTransform.parse("rot90+mirror_y+rot270").orElseThrow();
        assertArrayEquals(new int[] {2, -1, 3}, flip.apply(2, 1, 3));
    }

    @Test void blockDataStepsMatchThePositionMath() {
        assertEquals(List.of(MirrorTransform.Step.ROTATE_90, MirrorTransform.Step.MIRROR_X),
                MirrorTransform.DIAGONAL.steps());
        assertEquals(List.of(MirrorTransform.Step.MIRROR_X), MirrorTransform.MIRROR_X.steps());
        assertEquals(List.of(MirrorTransform.Step.MIRROR_Z), MirrorTransform.MIRROR_Z.steps());
        assertEquals(List.of(MirrorTransform.Step.ROTATE_90), MirrorTransform.ROTATE_90.steps());
        assertEquals(List.of(MirrorTransform.Step.ROTATE_270), MirrorTransform.ROTATE_270.steps());
        assertEquals(List.of(MirrorTransform.Step.ROTATE_180), MirrorTransform.MIRROR_XZ.steps());
        assertEquals(List.of(MirrorTransform.Step.MIRROR_Z), MirrorTransform.MIRROR_YZ.steps());
        assertEquals(List.of(MirrorTransform.Step.MIRROR_X), MirrorTransform.MIRROR_XY.steps());
        assertEquals(List.of(MirrorTransform.Step.ROTATE_90, MirrorTransform.Step.MIRROR_X),
                MirrorTransform.parse("rot90+mirror_x").orElseThrow().steps());
        for (MirrorTransform transform : MirrorTransform.all()) {
            int[] point = {2, 1, 3};
            assertArrayEquals(transform.apply(point[0], point[1], point[2]), stepsApplied(transform, point),
                    transform + " block-data steps must move like the matrix");
        }
    }

    @Test void verticalFlipsOnlyNegateY() {
        for (MirrorTransform transform : MirrorTransform.all()) {
            int[] moved = transform.apply(2, 1, 3);
            if (transform.verticalFlip()) {
                assertEquals(-1, moved[1], transform + " flips y");
            } else {
                assertEquals(1, moved[1], transform + " keeps y");
            }
        }
        assertTrue(MirrorTransform.MIRROR_Y.verticalFlip());
        assertFalse(MirrorTransform.MIRROR_X.verticalFlip());
        assertFalse(MirrorTransform.MIRROR_X.steps().isEmpty());
        assertTrue(MirrorTransform.MIRROR_Y.steps().isEmpty(), "a vertical flip has no block-data step");
    }

    @Test void equalityIgnoresHowTheTransformWasWritten() {
        assertEquals(MirrorTransform.parse("rot90+mirror_x").orElseThrow(), MirrorTransform.DIAGONAL);
        assertEquals(MirrorTransform.parse("rot90+mirror_x").orElseThrow().hashCode(),
                MirrorTransform.DIAGONAL.hashCode());
        assertNotEquals(MirrorTransform.MIRROR_X, MirrorTransform.MIRROR_Z);
        assertEquals(Optional.of(MirrorTransform.ROTATE_180), MirrorTransform.parse("r180"));
    }

    /** Applies the block-data steps (and the vertical flip) to an offset, like {@code MirrorBlocks}. */
    private static int[] stepsApplied(MirrorTransform transform, int[] point) {
        int[] m = IDENTITY.clone();
        for (MirrorTransform.Step step : transform.steps()) {
            m = multiply(step.matrix(), m);
        }
        if (transform.verticalFlip()) {
            m = multiply(VERTICAL, m);
        }
        return new int[] {
            m[0] * point[0] + m[1] * point[1] + m[2] * point[2],
            m[3] * point[0] + m[4] * point[1] + m[5] * point[2],
            m[6] * point[0] + m[7] * point[1] + m[8] * point[2]
        };
    }

    private static int[] multiply(int[] a, int[] b) {
        int[] out = new int[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int sum = 0;
                for (int k = 0; k < 3; k++) {
                    sum += a[row * 3 + k] * b[k * 3 + col];
                }
                out[row * 3 + col] = sum;
            }
        }
        return out;
    }
}
