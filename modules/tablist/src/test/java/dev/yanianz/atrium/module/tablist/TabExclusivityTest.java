package dev.yanianz.atrium.module.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.tablist.TabOwnership.Owner;
import org.junit.jupiter.api.Test;

/** Who shows each tab list part, and the exact values handed to TAB (empty versus null). */
class TabExclusivityTest {
    private static final TablistCapabilities ALL_TAB_FEATURES = TablistCapabilities.tab(true, true, true);
    private static final TablistCapabilities NO_TAB_FEATURES = TablistCapabilities.tab(false, false, false);

    @Test
    void tabEngineOverridesEveryPartTabRuns() {
        TabOwnership own = TabOwnership.plan(true, ALL_TAB_FEATURES, true, true);
        assertEquals(Owner.ATRIUM_VIA_TAB, own.headerFooter());
        assertEquals(Owner.ATRIUM_VIA_TAB, own.listNames());
        assertEquals(Owner.ATRIUM_VIA_TAB, own.sorting());
        assertEquals(Owner.TAB, own.nametags(), "Atrium has no nametag config: TAB keeps the text above heads");
    }

    @Test
    void partWhoseTabFeatureIsOffIsSentByAtriumNeverLeftEmpty() {
        // TAB's header-footer disabled (as in TAB's default config): TAB sends nothing, so Atrium sends it.
        TabOwnership own = TabOwnership.plan(true, TablistCapabilities.tab(false, true, true), true, true);
        assertEquals(Owner.ATRIUM_NATIVE, own.headerFooter());
        assertEquals(Owner.ATRIUM_VIA_TAB, own.listNames());
        TabOwnership none = TabOwnership.plan(true, NO_TAB_FEATURES, true, true);
        assertEquals(Owner.ATRIUM_NATIVE, none.headerFooter());
        assertEquals(Owner.ATRIUM_NATIVE, none.listNames());
        assertEquals(Owner.ATRIUM_NATIVE, none.sorting());
    }

    @Test
    void headerFooterIsAlwaysAtriumsWithTheTabEngine() {
        for (boolean header : new boolean[] {true, false}) {
            for (boolean names : new boolean[] {true, false}) {
                for (boolean sorting : new boolean[] {true, false}) {
                    TabOwnership own = TabOwnership.plan(true, TablistCapabilities.tab(header, names, sorting),
                            names, sorting);
                    assertTrue(own.headerFooter().atrium(), "never TAB's header");
                }
            }
        }
    }

    @Test
    void unmanagedPartsAreLeftToTabOnlyWhenAtriumIsConfiguredSo() {
        TabOwnership own = TabOwnership.plan(true, ALL_TAB_FEATURES, false, false);
        assertEquals(Owner.TAB, own.listNames());
        assertEquals(Owner.TAB, own.sorting());
        assertEquals(Owner.ATRIUM_VIA_TAB, own.headerFooter());
    }

    @Test
    void nativeEngineNeverTalksToTab() {
        TabOwnership own = TabOwnership.plan(false, ALL_TAB_FEATURES, true, true);
        assertEquals(Owner.ATRIUM_NATIVE, own.headerFooter());
        assertEquals(Owner.ATRIUM_NATIVE, own.listNames());
        assertEquals(Owner.ATRIUM_NATIVE, own.sorting());
        assertEquals(Owner.VANILLA, own.nametags());
        TabOwnership off = TabOwnership.plan(false, TablistCapabilities.nativeEngine(), false, false);
        assertEquals(Owner.VANILLA, off.listNames());
        assertEquals(Owner.VANILLA, off.sorting());
        for (Owner owner : Owner.values()) {
            assertFalse(owner.viaTab() && owner.nativeAtrium());
        }
    }

    @Test
    void emptyHeaderAndFooterAreEmptyOverridesNotResets() {
        TabOverrides values = TabOverrides.of(new TabBridge.Frame("", "", "&cName"));
        assertEquals("", values.header(), "\"\" hides TAB's header; null would fall back to it");
        assertEquals("", values.footer());
        TabOverrides missing = TabOverrides.of(new TabBridge.Frame(null, null, null));
        assertEquals("", missing.header());
        assertEquals("", missing.footer());
    }

    @Test
    void managedListNameReplacesTabsPrefixAndSuffix() {
        TabOverrides values = TabOverrides.of(new TabBridge.Frame("h", "f", "&c[Admin] &fAlex"));
        assertTrue(values.managesListName());
        assertEquals("", values.prefix(), "no TAB group prefix before Atrium's name: no double prefix");
        assertEquals("&c[Admin] &fAlex", values.name());
        assertEquals("", values.suffix());
    }

    @Test
    void unmanagedListNameResetsToTabsOwnFormatting() {
        TabOverrides values = TabOverrides.of(new TabBridge.Frame("h", "f", null));
        assertFalse(values.managesListName());
        assertNull(values.prefix(), "null = TAB's configured prefix");
        assertNull(values.name());
        assertNull(values.suffix());
        assertEquals("h", values.header());
    }

    @Test
    void debugRowFlagsStackingOnlyForManagedReadableParts() {
        assertTrue(new TabBridge.DebugRow("tab-prefix", "", "&c[Owner] ", "&c[Owner] ", true).mismatch());
        assertFalse(new TabBridge.DebugRow("tab-prefix", "", "", "&c[Owner] ", true).mismatch());
        assertFalse(new TabBridge.DebugRow("nametag-prefix", null, "&c", "&c", true).mismatch());
        assertFalse(new TabBridge.DebugRow("header", "x", null, null, false).mismatch());
    }
}
