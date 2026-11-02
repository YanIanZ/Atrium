package dev.yanianz.atrium.module.kits;

/**
 * Slot layout of the read-only kit item preview (6 rows): rows 0-2 the main inventory, row 3 the hotbar, row 4
 * the armour (helmet first) and the off hand, row 5 the navigation bar with the kit info in the middle.
 */
final class KitLayout {
    static final int ROWS = 6;
    static final int ARMOR_ROW_START = 36;
    static final int OFFHAND_SLOT = 40;
    static final int INFO = 49;

    private KitLayout() {}

    /** Menu slot showing kit slot {@code kitSlot} ({@link KitItems} order), or -1 when it is not shown. */
    static int displaySlot(int kitSlot) {
        if (kitSlot >= 9 && kitSlot < 36) return kitSlot - 9;
        if (kitSlot >= 0 && kitSlot < 9) return 27 + kitSlot;
        if (kitSlot >= KitItems.ARMOR_START && kitSlot < KitItems.OFFHAND) {
            return ARMOR_ROW_START + (3 - (kitSlot - KitItems.ARMOR_START));
        }
        if (kitSlot == KitItems.OFFHAND) return OFFHAND_SLOT;
        return -1;
    }
}
