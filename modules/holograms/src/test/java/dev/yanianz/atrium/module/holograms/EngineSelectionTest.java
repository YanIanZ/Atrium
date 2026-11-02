package dev.yanianz.atrium.module.holograms;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineSelectionTest {
    @Test void autoPrefersDecentThenFancyThenNative() {
        assertEquals(EngineKind.DECENTHOLOGRAMS,
                EngineSelection.select(EngineKind.AUTO, true, true, false, true).engine());
        assertEquals(EngineKind.FANCYHOLOGRAMS,
                EngineSelection.select(EngineKind.AUTO, false, true, false, true).engine());
        assertEquals(EngineKind.NATIVE,
                EngineSelection.select(EngineKind.AUTO, false, false, false, true).engine());
    }

    @Test void autoSkipsOldDecentHologramsOnFolia() {
        EngineSelection.Selection selection = EngineSelection.select(EngineKind.AUTO, true, true, true, false);
        assertEquals(EngineKind.FANCYHOLOGRAMS, selection.engine());
        assertFalse(selection.fallback());
        assertEquals(EngineKind.NATIVE,
                EngineSelection.select(EngineKind.AUTO, true, false, true, false).engine());
    }

    @Test void explicitNativeNeverFallsBack() {
        EngineSelection.Selection selection = EngineSelection.select(EngineKind.NATIVE, true, true, false, true);
        assertEquals(EngineKind.NATIVE, selection.engine());
        assertFalse(selection.fallback());
    }

    @Test void missingPluginFallsBackWithReason() {
        EngineSelection.Selection selection = EngineSelection.select(EngineKind.DECENTHOLOGRAMS, false, true, false, false);
        assertEquals(EngineKind.NATIVE, selection.engine());
        assertTrue(selection.fallback());
        assertEquals(EngineSelection.PLUGIN_MISSING, selection.reason());

        selection = EngineSelection.select(EngineKind.FANCYHOLOGRAMS, true, false, false, true);
        assertEquals(EngineKind.NATIVE, selection.engine());
        assertEquals(EngineSelection.PLUGIN_MISSING, selection.reason());
    }

    @Test void oldDecentHologramsIsRejectedOnFolia() {
        EngineSelection.Selection selection = EngineSelection.select(EngineKind.DECENTHOLOGRAMS, true, false, true, false);
        assertEquals(EngineKind.NATIVE, selection.engine());
        assertTrue(selection.fallback());
        assertEquals(EngineSelection.FOLIA_UNSUPPORTED, selection.reason());
    }

    @Test void supportedExplicitEngineIsUsedDirectly() {
        EngineSelection.Selection selection = EngineSelection.select(EngineKind.DECENTHOLOGRAMS, true, false, false, true);
        assertEquals(EngineKind.DECENTHOLOGRAMS, selection.engine());
        assertFalse(selection.fallback());
        assertEquals("", selection.reason());
    }

    @Test void kindParsingAcceptsIdsAndAliases() {
        assertEquals(EngineKind.NATIVE, EngineKind.parse("native").orElseThrow());
        assertEquals(EngineKind.NATIVE, EngineKind.parse("internal").orElseThrow());
        assertEquals(EngineKind.DECENTHOLOGRAMS, EngineKind.parse("DH").orElseThrow());
        assertEquals(EngineKind.FANCYHOLOGRAMS, EngineKind.parse("fancy-holograms").orElseThrow());
        assertEquals(EngineKind.AUTO, EngineKind.parse("auto").orElseThrow());
        assertTrue(EngineKind.parse("nope").isEmpty());
    }
    @Test void disablingTheActiveEnginesPluginFallsBackToNative() {
        assertTrue(EngineSelection.fallBackOnDisable(EngineKind.DECENTHOLOGRAMS, "DecentHolograms"));
        assertTrue(EngineSelection.fallBackOnDisable(EngineKind.FANCYHOLOGRAMS, "FancyHolograms"));
        assertFalse(EngineSelection.fallBackOnDisable(EngineKind.DECENTHOLOGRAMS, "FancyHolograms"));
        assertFalse(EngineSelection.fallBackOnDisable(EngineKind.NATIVE, "DecentHolograms"));
        assertFalse(EngineSelection.fallBackOnDisable(EngineKind.FANCYHOLOGRAMS, "Essentials"));
    }

    @Test void reEnablingThePluginReturnsOnlyWhenWanted() {
        // Fell back because the plugin was disabled: come back (auto or explicit).
        assertTrue(EngineSelection.returnOnEnable(EngineKind.NATIVE, EngineKind.AUTO,
                EngineSelection.PLUGIN_DISABLED, "DecentHolograms", EngineKind.DECENTHOLOGRAMS));
        // Explicitly configured but missing at boot (load order): use it once it is enabled.
        assertTrue(EngineSelection.returnOnEnable(EngineKind.NATIVE, EngineKind.FANCYHOLOGRAMS,
                EngineSelection.PLUGIN_MISSING, "FancyHolograms", EngineKind.FANCYHOLOGRAMS));
        // auto on a server that never used it: no surprise switch.
        assertFalse(EngineSelection.returnOnEnable(EngineKind.NATIVE, EngineKind.AUTO, "", "DecentHolograms",
                EngineKind.DECENTHOLOGRAMS));
        // Already on an external engine, configured native, or the selection prefers another plugin.
        assertFalse(EngineSelection.returnOnEnable(EngineKind.FANCYHOLOGRAMS, EngineKind.DECENTHOLOGRAMS,
                EngineSelection.PLUGIN_DISABLED, "DecentHolograms", EngineKind.DECENTHOLOGRAMS));
        assertFalse(EngineSelection.returnOnEnable(EngineKind.NATIVE, EngineKind.NATIVE,
                EngineSelection.PLUGIN_DISABLED, "DecentHolograms", EngineKind.NATIVE));
        assertFalse(EngineSelection.returnOnEnable(EngineKind.NATIVE, EngineKind.AUTO,
                EngineSelection.PLUGIN_DISABLED, "FancyHolograms", EngineKind.DECENTHOLOGRAMS));
    }
}
