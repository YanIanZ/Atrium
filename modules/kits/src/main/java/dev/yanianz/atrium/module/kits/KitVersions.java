package dev.yanianz.atrium.module.kits;

/**
 * Optimistic locking of kit rows. Every write is {@code UPDATE ... WHERE name = ? AND version = ?} with the
 * version the writer started from; exactly one affected row means nobody changed the kit in between.
 */
final class KitVersions {
    private KitVersions() {}

    /** The version a successful write stores. */
    static long next(long version) {
        if (version < 0) throw new IllegalArgumentException("negative version");
        return version == Long.MAX_VALUE ? 1 : version + 1;
    }

    /** Whether a versioned write actually happened (0 rows = the row changed or is gone). */
    static boolean applied(int affectedRows) {
        return affectedRows == 1;
    }
}
