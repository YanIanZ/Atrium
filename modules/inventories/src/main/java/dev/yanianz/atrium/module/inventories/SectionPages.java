package dev.yanianz.atrium.module.inventories;

/**
 * Page arithmetic for showing one snapshot section (inventory or ender chest) in a chest menu with a navigation
 * row. At most five content rows fit above the navigation row, so a section of up to 45 slots is one page
 * (vanilla inventory: 41, vanilla ender chest: 27) and a six-row ender chest (54) needs two. Pure and
 * independent of Bukkit.
 */
final class SectionPages {
    static final int COLUMNS = 9;
    /** Five content rows above the navigation row. */
    static final int PAGE_SLOTS = 5 * COLUMNS;

    private SectionPages() {}

    static int pages(int slots) {
        return Math.max(1, (Math.max(0, slots) + PAGE_SLOTS - 1) / PAGE_SLOTS);
    }

    /** Menu rows: the content rows the section needs on its largest page, plus the navigation row. */
    static int rows(int slots) {
        int content = Math.min(PAGE_SLOTS, Math.max(1, slots));
        return (content + COLUMNS - 1) / COLUMNS + 1;
    }

    static int clamp(int page, int slots) {
        return Math.clamp(page, 0, pages(slots) - 1);
    }

    /** First section slot shown on {@code page} (0-based). */
    static int first(int page, int slots) {
        return clamp(page, slots) * PAGE_SLOTS;
    }

    /** Number of section slots shown on {@code page}. */
    static int count(int page, int slots) {
        return Math.clamp(Math.max(0, slots) - first(page, slots), 0, PAGE_SLOTS);
    }
}
