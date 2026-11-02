package dev.yanianz.atrium.module.performance;

/** Cap arithmetic kept separate from Bukkit so the boundary cases are unit-tested. */
final class EntityLimits {
    private EntityLimits() {}

    /**
     * Cheap path: with fewer than half the cap present in total, no category can reach the cap,
     * so the per-entity classification can be skipped. Uses multiplication to avoid rounding down.
     */
    static boolean underHalf(int totalInChunk, int cap) {
        return cap > 0 && (long) totalInChunk * 2L < cap;
    }

    /** {@code current} excludes the candidate entity. A cap of 0 disables the category. */
    static boolean allowed(int current, int cap) {
        return cap <= 0 || current < cap;
    }
}
