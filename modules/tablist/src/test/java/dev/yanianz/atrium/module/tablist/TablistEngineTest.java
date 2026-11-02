package dev.yanianz.atrium.module.tablist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TablistEngineTest {
    @Test
    void autoUsesTabWhenEnabledAndNativeOtherwise() {
        TablistEngineSelection.Selection withTab = TablistEngineSelection.select(TablistEngine.AUTO, true);
        assertEquals(TablistEngine.TAB, withTab.engine());
        assertFalse(withTab.fallback());
        TablistEngineSelection.Selection without = TablistEngineSelection.select(TablistEngine.AUTO, false);
        assertEquals(TablistEngine.NATIVE, without.engine());
        assertFalse(without.fallback(), "auto without TAB is not a fallback");
        assertEquals("", without.reason());
        assertEquals(TablistEngine.TAB, TablistEngineSelection.select(null, true).engine());
    }

    @Test
    void explicitEnginesAreUsedDirectly() {
        assertEquals(TablistEngine.NATIVE, TablistEngineSelection.select(TablistEngine.NATIVE, true).engine());
        assertFalse(TablistEngineSelection.select(TablistEngine.NATIVE, true).fallback());
        assertEquals(TablistEngine.TAB, TablistEngineSelection.select(TablistEngine.TAB, true).engine());
    }

    @Test
    void explicitTabWithoutThePluginFallsBackToNativeWithReason() {
        TablistEngineSelection.Selection selection = TablistEngineSelection.select(TablistEngine.TAB, false);
        assertEquals(TablistEngine.NATIVE, selection.engine());
        assertTrue(selection.fallback());
        assertEquals(TablistEngineSelection.PLUGIN_MISSING, selection.reason());
    }

    @Test
    void disablingTabFallsBackOnlyWhenTabIsActive() {
        assertTrue(TablistEngineSelection.fallBackOnDisable(TablistEngine.TAB, "TAB"));
        assertFalse(TablistEngineSelection.fallBackOnDisable(TablistEngine.NATIVE, "TAB"));
        assertFalse(TablistEngineSelection.fallBackOnDisable(TablistEngine.TAB, "PlaceholderAPI"));
        assertFalse(TablistEngineSelection.fallBackOnDisable(TablistEngine.TAB, "tab"), "plugin names are exact");
    }

    @Test
    void enablingTabReturnsOnlyWhenWanted() {
        assertTrue(TablistEngineSelection.returnOnEnable(TablistEngine.NATIVE, TablistEngine.TAB,
                TablistEngineSelection.PLUGIN_MISSING, "TAB"));
        assertTrue(TablistEngineSelection.returnOnEnable(TablistEngine.NATIVE, TablistEngine.AUTO,
                TablistEngineSelection.PLUGIN_DISABLED, "TAB"));
        // auto on a server that started without TAB: no surprise switch.
        assertFalse(TablistEngineSelection.returnOnEnable(TablistEngine.NATIVE, TablistEngine.AUTO, "", "TAB"));
        assertFalse(TablistEngineSelection.returnOnEnable(TablistEngine.NATIVE, TablistEngine.NATIVE,
                TablistEngineSelection.PLUGIN_DISABLED, "TAB"));
        assertFalse(TablistEngineSelection.returnOnEnable(TablistEngine.TAB, TablistEngine.TAB, "", "TAB"));
        assertFalse(TablistEngineSelection.returnOnEnable(TablistEngine.NATIVE, TablistEngine.TAB, "", "Vault"));
    }

    @Test
    void engineNamesParse() {
        assertEquals(TablistEngine.AUTO, TablistEngine.parse(" Auto ").orElseThrow());
        assertEquals(TablistEngine.AUTO, TablistEngine.parse("").orElseThrow());
        assertEquals(TablistEngine.NATIVE, TablistEngine.parse("native").orElseThrow());
        assertEquals(TablistEngine.TAB, TablistEngine.parse("TAB").orElseThrow());
        assertTrue(TablistEngine.parse("tablistplus").isEmpty());
        assertTrue(TablistEngine.parse(null).isEmpty());
        assertEquals(List.of("auto", "native", "tab"), TablistEngine.names());
    }

    @Test
    void nativeControlsEveryPart() {
        TablistCapabilities capabilities = TablistCapabilities.nativeEngine();
        assertTrue(capabilities.missing().isEmpty());
        for (TablistCapabilities.Feature feature : TablistCapabilities.Feature.values()) {
            assertTrue(capabilities.supports(feature), feature.key());
        }
    }

    @Test
    void tabCapabilitiesFollowTabsEnabledFeatures() {
        TablistCapabilities all = TablistCapabilities.tab(true, true, true);
        assertTrue(all.missing().isEmpty());
        TablistCapabilities headerOnly = TablistCapabilities.tab(true, false, false);
        assertEquals(List.of(TablistCapabilities.Feature.HEADER_FOOTER, TablistCapabilities.Feature.PER_PLAYER_TEXT),
                headerOnly.supported());
        assertEquals("list-names, group-sorting, name-sorting",
                TablistCapabilities.describe(headerOnly.missing()));
        TablistCapabilities sortingOnly = TablistCapabilities.tab(false, false, true);
        assertTrue(sortingOnly.supports(TablistCapabilities.Feature.GROUP_SORTING));
        assertTrue(sortingOnly.supports(TablistCapabilities.Feature.NAME_SORTING));
        assertFalse(sortingOnly.supports(TablistCapabilities.Feature.HEADER_FOOTER));
        assertTrue(TablistCapabilities.tab(false, false, false).supports(TablistCapabilities.Feature.PER_PLAYER_TEXT),
                "Atrium renders per player on every engine");
        assertEquals("none", TablistCapabilities.describe(List.of()));
    }
}
