package dev.yanianz.atrium.module.player;

/**
 * Slot arithmetic of the /invsee and /enderchest views, sized from the live (or stored) inventory instead of the
 * vanilla 41/27 slots: Paper forks and plugins resize ender chests up to six rows. Pure and independent of Bukkit.
 */
final class InventoryLayout {
    static final int COLUMNS = 9;
    /** The largest chest view Paper can create. */
    static final int MAX_SLOTS = 54;
    /** The classic /invsee view: 41 live slots, filler and an info slot in the last row. */
    static final int INVENTORY_VIEW = 45;
    /** Five content rows above the navigation row of a paged menu. */
    static final int PAGE_SLOTS = 5 * COLUMNS;

    private InventoryLayout() {}

    // ---- live view ----

    /**
     * Size of the live display. An ender chest view is exactly as large as the ender chest (rounded up to whole
     * rows): 27 → 3 rows, 54 → 6 rows. A player inventory view keeps the classic 45 slots and grows only when
     * a fork reports more than 44 live slots, so there is always room for the info slot when possible.
     */
    static int displaySize(boolean ender, int liveSlots) {
        int live = Math.max(0, liveSlots);
        if (ender) {
            return Math.clamp(roundUp(live), COLUMNS, MAX_SLOTS);
        }
        return Math.clamp(roundUp(live + 1), INVENTORY_VIEW, MAX_SLOTS);
    }

    /** Display slots that mirror a live slot: the live size, cut to the display. */
    static int shownSlots(int displaySize, int liveSlots) {
        return Math.clamp(liveSlots, 0, Math.max(0, displaySize));
    }

    /** The info slot of a player inventory view (the last slot), or -1 when every slot shows a live item. */
    static int infoSlot(int displaySize, int liveSlots) {
        return liveSlots < displaySize ? displaySize - 1 : -1;
    }

    /** True when a click on {@code slot} of the display may exchange the matching live slot. */
    static boolean editable(int slot, int displaySize, int liveSlots) {
        return slot >= 0 && slot < shownSlots(displaySize, liveSlots);
    }

    // ---- paged snapshot menus ----

    static int pages(int slots) {
        return Math.max(1, (Math.max(0, slots) + PAGE_SLOTS - 1) / PAGE_SLOTS);
    }

    /** Menu rows: the content rows the section needs on its largest page, plus the navigation row. */
    static int rows(int slots) {
        int content = Math.min(PAGE_SLOTS, Math.max(1, slots));
        return (content + COLUMNS - 1) / COLUMNS + 1;
    }

    static int clampPage(int page, int slots) {
        return Math.clamp(page, 0, pages(slots) - 1);
    }

    /** First section slot shown on {@code page} (0-based). */
    static int first(int page, int slots) {
        return clampPage(page, slots) * PAGE_SLOTS;
    }

    /** Number of section slots shown on {@code page}. */
    static int count(int page, int slots) {
        return Math.clamp(Math.max(0, slots) - first(page, slots), 0, PAGE_SLOTS);
    }

    private static int roundUp(int slots) {
        return (slots + COLUMNS - 1) / COLUMNS * COLUMNS;
    }
}
