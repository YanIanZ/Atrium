package dev.yanianz.atrium.module.holograms;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineCapabilitiesTest {
    @Test void nativeSupportsEveryFeature() {
        EngineCapabilities capabilities = EngineCapabilities.nativeEngine();
        assertTrue(capabilities.missing().isEmpty());
        for (EngineCapabilities.Feature feature : EngineCapabilities.Feature.values()) {
            assertTrue(capabilities.supports(feature), feature.key());
        }
    }

    @Test void decentHologramsSupportsPagesItemsAndViewerControlOnly() {
        EngineCapabilities capabilities = EngineCapabilities.decentHolograms();
        assertTrue(capabilities.supports(EngineCapabilities.Feature.PAGES));
        assertTrue(capabilities.supports(EngineCapabilities.Feature.PER_VIEWER_PAGES));
        assertTrue(capabilities.supports(EngineCapabilities.Feature.ITEM_LINES));
        assertTrue(capabilities.supports(EngineCapabilities.Feature.PER_VIEWER_VISIBILITY));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.PER_VIEWER_TEXT));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.CLICK_ACTIONS));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.BLOCK_LINES));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.IMAGE_LINES));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.LINE_OFFSETS));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.DYNAMIC_TEXT));
    }

    @Test void fancyHologramsIsTextOnly() {
        EngineCapabilities capabilities = EngineCapabilities.fancyHolograms();
        assertFalse(capabilities.supports(EngineCapabilities.Feature.PAGES));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.ITEM_LINES));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.BLOCK_LINES));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.CLICK_ACTIONS));
        assertFalse(capabilities.supports(EngineCapabilities.Feature.LINE_OFFSETS));
        assertTrue(capabilities.supports(EngineCapabilities.Feature.PER_VIEWER_VISIBILITY));
        assertEquals(List.of(EngineCapabilities.Feature.PER_VIEWER_VISIBILITY), capabilities.supported());
    }

    @Test void describesFeaturesWithStableKeys() {
        assertEquals("pages, item-lines", EngineCapabilities.describe(List.of(
                EngineCapabilities.Feature.PAGES, EngineCapabilities.Feature.ITEM_LINES)));
        assertEquals("none", EngineCapabilities.describe(List.of()));
        assertEquals("holograms.feature-click-actions",
                EngineCapabilities.capabilityKey(EngineCapabilities.Feature.CLICK_ACTIONS));
    }

    @Test void decentVersionDecidesFoliaSupport() {
        assertTrue(DecentHologramsVersion.supportsFolia("2.10.0"));
        assertTrue(DecentHologramsVersion.supportsFolia("2.10.2"));
        assertTrue(DecentHologramsVersion.supportsFolia("2.11.0-SNAPSHOT"));
        assertTrue(DecentHologramsVersion.supportsFolia("3.0.0"));
        assertFalse(DecentHologramsVersion.supportsFolia("2.9.9"));
        assertFalse(DecentHologramsVersion.supportsFolia("2.8.17"));
        assertFalse(DecentHologramsVersion.supportsFolia(""));
        assertFalse(DecentHologramsVersion.supportsFolia("unknown"));
        assertArrayEquals(new int[] {2, 10, 2}, DecentHologramsVersion.parse("2.10.2"));
    }
}
