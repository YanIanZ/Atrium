package dev.yanianz.atrium.module.holograms;

/**
 * Page cycling (0-based). Every index is clamped first, so a viewer whose page was removed falls back to
 * the first page instead of losing the hologram.
 */
final class Pages {
    private Pages() {}

    static int clamp(int page, int count) {
        return count <= 0 || page < 0 || page >= count ? 0 : page;
    }

    static int next(int page, int count) {
        return count <= 1 ? 0 : (clamp(page, count) + 1) % count;
    }

    static int previous(int page, int count) {
        return count <= 1 ? 0 : (clamp(page, count) - 1 + count) % count;
    }
}
