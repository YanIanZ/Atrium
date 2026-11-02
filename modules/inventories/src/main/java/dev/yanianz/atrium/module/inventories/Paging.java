package dev.yanianz.atrium.module.inventories;

/** Page arithmetic for /invlist. Pure and independent of Bukkit. */
final class Paging {
    private Paging() {}

    static int pages(int total, int pageSize) {
        int size = Math.max(1, pageSize);
        return Math.max(1, (Math.max(0, total) + size - 1) / size);
    }

    /** Clamps a requested page into {@code 1..pages}. */
    static int clamp(int requested, int total, int pageSize) {
        return Math.clamp(requested, 1, pages(total, pageSize));
    }
}
