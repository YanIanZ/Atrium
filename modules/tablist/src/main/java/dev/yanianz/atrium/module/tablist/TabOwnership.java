package dev.yanianz.atrium.module.tablist;

import java.util.Locale;

/**
 * Who shows each part of the tab list. Pure, so the exclusivity rules can be tested without a server.
 *
 * <p>Atrium's configuration is the only source of truth for every part it is configured to manage. With the
 * {@code tab} engine a part goes through TAB's API when TAB runs the matching feature (TAB would otherwise draw
 * its own content there); when that TAB feature is disabled TAB draws nothing for the part, so Atrium sends it
 * itself exactly as the native engine does. TAB's own content is only shown for parts Atrium is configured not
 * to manage, and for nametags above heads, which Atrium has no configuration for.
 */
record TabOwnership(Owner headerFooter, Owner listNames, Owner sorting, Owner nametags) {
    enum Owner {
        /** Atrium sends it itself (Paper API). */
        ATRIUM_NATIVE("atrium (native)"),
        /** Atrium's value, handed to TAB through its API; TAB's own config is overridden. */
        ATRIUM_VIA_TAB("atrium (through TAB)"),
        /** Atrium does not manage it; TAB's own configuration decides. */
        TAB("TAB's own config"),
        /** Nobody manages it (vanilla behaviour). */
        VANILLA("vanilla");

        final String label;

        Owner(String label) {
            this.label = label;
        }

        boolean atrium() {
            return this == ATRIUM_NATIVE || this == ATRIUM_VIA_TAB;
        }

        boolean viaTab() {
            return this == ATRIUM_VIA_TAB;
        }

        boolean nativeAtrium() {
            return this == ATRIUM_NATIVE;
        }

        String key() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    /**
     * @param tabEngine    the TAB engine is active
     * @param tab          which TAB features currently exist (ignored for native)
     * @param setListName  Atrium manages list names (set-list-name)
     * @param sortByGroup  Atrium sorts the list (sort-by-group)
     */
    static TabOwnership plan(boolean tabEngine, TablistCapabilities tab, boolean setListName, boolean sortByGroup) {
        if (!tabEngine) {
            return new TabOwnership(Owner.ATRIUM_NATIVE,
                    setListName ? Owner.ATRIUM_NATIVE : Owner.VANILLA,
                    sortByGroup ? Owner.ATRIUM_NATIVE : Owner.VANILLA,
                    Owner.VANILLA);
        }
        Owner header = tab.headerFooter() ? Owner.ATRIUM_VIA_TAB : Owner.ATRIUM_NATIVE;
        Owner names = !setListName ? Owner.TAB : tab.listNames() ? Owner.ATRIUM_VIA_TAB : Owner.ATRIUM_NATIVE;
        Owner order = !sortByGroup ? Owner.TAB : tab.groupSorting() ? Owner.ATRIUM_VIA_TAB : Owner.ATRIUM_NATIVE;
        // Atrium has no nametag configuration: the text above heads stays TAB's (scoreboard-teams).
        return new TabOwnership(header, names, order, Owner.TAB);
    }
}
