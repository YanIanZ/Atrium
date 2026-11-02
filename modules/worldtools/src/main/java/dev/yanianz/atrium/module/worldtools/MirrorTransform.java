package dev.yanianz.atrium.module.worldtools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One mirror or rotation of a block around the centre block, plus the block-data operations needed to
 * keep a directional block (stairs, a chest, a log with an axis) pointing the mirrored way.
 *
 * <p>The position math is a signed permutation matrix on the offset from the centre block, so the centre
 * block maps onto itself and mirroring twice is the identity again. Block data is transformed by the same
 * steps using Bukkit's {@code BlockData#rotate}/{@code BlockData#mirror} (see {@link MirrorBlocks}).
 *
 * <p>Vertical flips ({@code mirror_y}, {@code mirror_xy}, ...) move the position in Y only. Bukkit's block
 * data API only rotates and mirrors around the Y axis, so a vertical flip leaves the orientation unchanged.
 *
 * <p>Pure: no Bukkit types, so every transform, composition and alias is unit tested.
 */
final class MirrorTransform {
    /** Primitive block-data operations. Each one is also a 3x3 matrix used for the position math. */
    enum Step {
        ROTATE_90(new int[] {0, 0, -1, 0, 1, 0, 1, 0, 0}),
        ROTATE_180(new int[] {-1, 0, 0, 0, 1, 0, 0, 0, -1}),
        ROTATE_270(new int[] {0, 0, 1, 0, 1, 0, -1, 0, 0}),
        /** Mirror across the YZ plane (x becomes -x). */
        MIRROR_X(new int[] {-1, 0, 0, 0, 1, 0, 0, 0, 1}),
        /** Mirror across the XY plane (z becomes -z). */
        MIRROR_Z(new int[] {1, 0, 0, 0, 1, 0, 0, 0, -1});

        private final int[] matrix;

        Step(int[] matrix) {
            this.matrix = matrix;
        }

        int[] matrix() {
            return matrix.clone();
        }
    }

    private static final int[] IDENTITY = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    private static final int[] VERTICAL = {1, 0, 0, 0, -1, 0, 0, 0, 1};

    static final MirrorTransform MIRROR_X = new MirrorTransform("mirror_x", List.of(Step.MIRROR_X), false);
    static final MirrorTransform MIRROR_Y = new MirrorTransform("mirror_y", List.of(), true);
    static final MirrorTransform MIRROR_Z = new MirrorTransform("mirror_z", List.of(Step.MIRROR_Z), false);
    static final MirrorTransform MIRROR_XY = new MirrorTransform("mirror_xy", List.of(Step.MIRROR_X), true);
    static final MirrorTransform MIRROR_YZ = new MirrorTransform("mirror_yz", List.of(Step.MIRROR_Z), true);
    static final MirrorTransform MIRROR_XZ = new MirrorTransform("mirror_xz", List.of(Step.ROTATE_180), false);
    static final MirrorTransform MIRROR_XYZ = new MirrorTransform("mirror_xyz", List.of(Step.ROTATE_180), true);
    /** Mirror across the x = z plane (x and z swap). */
    static final MirrorTransform DIAGONAL = new MirrorTransform("diagonal",
            List.of(Step.ROTATE_90, Step.MIRROR_X), false);
    /** Mirror across the x = -z plane. */
    static final MirrorTransform ANTIDIAGONAL = new MirrorTransform("antidiagonal",
            List.of(Step.ROTATE_90, Step.MIRROR_Z), false);
    static final MirrorTransform ROTATE_90 = new MirrorTransform("rotate_90", List.of(Step.ROTATE_90), false);
    static final MirrorTransform ROTATE_180 = new MirrorTransform("rotate_180", List.of(Step.ROTATE_180), false);
    static final MirrorTransform ROTATE_270 = new MirrorTransform("rotate_270", List.of(Step.ROTATE_270), false);
    static final MirrorTransform ROTATE_90_YFLIP = new MirrorTransform("rotate_90_yflip",
            List.of(Step.ROTATE_90), true);
    static final MirrorTransform ROTATE_270_YFLIP = new MirrorTransform("rotate_270_yflip",
            List.of(Step.ROTATE_270), true);
    static final MirrorTransform DIAGONAL_YFLIP = new MirrorTransform("diagonal_yflip",
            List.of(Step.ROTATE_90, Step.MIRROR_X), true);
    static final MirrorTransform ANTIDIAGONAL_YFLIP = new MirrorTransform("antidiagonal_yflip",
            List.of(Step.ROTATE_90, Step.MIRROR_Z), true);

    private static final List<MirrorTransform> ALL = List.of(MIRROR_X, MIRROR_Y, MIRROR_Z, MIRROR_XY, MIRROR_YZ,
            MIRROR_XZ, MIRROR_XYZ, DIAGONAL, ANTIDIAGONAL, ROTATE_90, ROTATE_180, ROTATE_270, ROTATE_90_YFLIP,
            ROTATE_270_YFLIP, DIAGONAL_YFLIP, ANTIDIAGONAL_YFLIP);
    private static final Map<String, MirrorTransform> ALIASES = aliases();

    private final String id;
    private final List<Step> steps;
    private final boolean verticalFlip;
    private final int[] matrix;

    private MirrorTransform(String id, List<Step> steps, boolean verticalFlip) {
        this.id = id;
        this.steps = List.copyOf(steps);
        this.verticalFlip = verticalFlip;
        int[] m = IDENTITY.clone();
        for (Step step : this.steps) {
            m = multiply(step.matrix(), m);
        }
        if (verticalFlip) {
            m = multiply(VERTICAL, m);
        }
        this.matrix = m;
    }

    private static Map<String, MirrorTransform> aliases() {
        Map<String, MirrorTransform> map = new LinkedHashMap<>();
        register(map, MIRROR_X, "x", "xr", "mirrorx", "east_west");
        register(map, MIRROR_Y, "y", "yr", "mirrory", "up_down");
        register(map, MIRROR_Z, "z", "zr", "mirrorz", "north_south");
        register(map, MIRROR_XY, "xy", "xy180");
        register(map, MIRROR_YZ, "yz", "yz180");
        register(map, MIRROR_XZ, "xz", "zx", "xz180", "zx180");
        register(map, MIRROR_XYZ, "xyz", "xyz180", "inversion", "invert");
        register(map, DIAGONAL, "diag", "d", "diag_xz");
        register(map, ANTIDIAGONAL, "anti", "adiag", "antidiag", "anti_diag");
        register(map, ROTATE_90, "rot90", "r90", "y90");
        register(map, ROTATE_180, "rot180", "r180", "y180");
        register(map, ROTATE_270, "rot270", "r270", "y270", "rot90r");
        register(map, ROTATE_90_YFLIP, "rot90y", "r90y", "y90y");
        register(map, ROTATE_270_YFLIP, "rot270y", "r270y", "y270y");
        register(map, DIAGONAL_YFLIP, "diagy", "diag_y");
        register(map, ANTIDIAGONAL_YFLIP, "antiy", "adiagy", "anti_diag_y");
        return Map.copyOf(map);
    }

    private static void register(Map<String, MirrorTransform> map, MirrorTransform transform, String... aliases) {
        map.put(transform.id, transform);
        for (String alias : aliases) {
            map.put(alias, transform);
        }
    }

    String id() {
        return id;
    }

    /** Block-data steps in application order; a vertical flip is not part of this list. */
    List<Step> steps() {
        return steps;
    }

    boolean verticalFlip() {
        return verticalFlip;
    }

    /** The transform's 3x3 matrix, row-major. Exposed for tests and for {@link #determinant()}. */
    int[] matrix() {
        return matrix.clone();
    }

    int determinant() {
        return matrix[0] * (matrix[4] * matrix[8] - matrix[5] * matrix[7])
                - matrix[1] * (matrix[3] * matrix[8] - matrix[5] * matrix[6])
                + matrix[2] * (matrix[3] * matrix[7] - matrix[4] * matrix[6]);
    }

    /** {@code true} for a mirror (orientation changing), {@code false} for a pure rotation. */
    boolean mirrored() {
        return determinant() < 0;
    }

    /** Offsets are relative to the centre block. Always returns a new array of three integers. */
    int[] apply(int dx, int dy, int dz) {
        return new int[] {
            matrix[0] * dx + matrix[1] * dy + matrix[2] * dz,
            matrix[3] * dx + matrix[4] * dy + matrix[5] * dz,
            matrix[6] * dx + matrix[7] * dy + matrix[8] * dz
        };
    }

    /** Applies this transform to an absolute block position around {@code (cx, cy, cz)}. */
    int[] applyAt(int cx, int cy, int cz, int x, int y, int z) {
        int[] offset = apply(x - cx, y - cy, z - cz);
        return new int[] {cx + offset[0], cy + offset[1], cz + offset[2]};
    }

    /**
     * Composes two transforms: {@code first} is applied, then {@code second}. Compositions are what
     * {@code /mirror set rot90+mirror_x} accepts, so a server can offer more than one operation at once.
     */
    static MirrorTransform compose(MirrorTransform first, MirrorTransform second) {
        List<Step> steps = new ArrayList<>(first.steps);
        steps.addAll(second.steps);
        return new MirrorTransform(first.id + "+" + second.id, steps, first.verticalFlip ^ second.verticalFlip);
    }

    /** Parses a name or an alias, or two or more of them joined with {@code +} (applied left to right). */
    static Optional<MirrorTransform> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String raw = text.strip().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        MirrorTransform result = null;
        for (String part : raw.split("\\+")) {
            MirrorTransform simple = ALIASES.get(part.strip());
            if (simple == null) {
                return Optional.empty();
            }
            result = result == null ? simple : compose(result, simple);
        }
        return Optional.ofNullable(result);
    }

    static List<MirrorTransform> all() {
        return ALL;
    }

    static List<String> names() {
        return ALL.stream().map(MirrorTransform::id).toList();
    }

    @Override
    public String toString() {
        return id;
    }

    /** Two transforms are equal when they move a block to the same place, however they were written. */
    @Override
    public boolean equals(Object other) {
        return other instanceof MirrorTransform transform && Arrays.equals(matrix, transform.matrix);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(matrix);
    }

    /** {@code a} applied after {@code b}. */
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
