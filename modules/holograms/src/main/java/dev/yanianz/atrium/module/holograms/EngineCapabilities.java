package dev.yanianz.atrium.module.holograms;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What an engine can reproduce. The editor refuses a feature the active engine does not support instead of
 * silently storing something that would never be shown. Stored data is always kept, so switching back to a
 * richer engine restores the feature.
 */
record EngineCapabilities(boolean pages, boolean perViewerPages, boolean perViewerText, boolean clickActions,
        boolean itemLines, boolean blockLines, boolean lineOffsets, boolean perViewerVisibility,
        boolean dynamicText) {

    /** Capabilities with a stable key used by the lang files. */
    enum Feature {
        PAGES("pages"),
        PER_VIEWER_PAGES("per-viewer-pages"),
        PER_VIEWER_TEXT("per-viewer-text"),
        CLICK_ACTIONS("click-actions"),
        ITEM_LINES("item-lines"),
        BLOCK_LINES("block-lines"),
        LINE_OFFSETS("line-offsets"),
        PER_VIEWER_VISIBILITY("per-viewer-visibility"),
        DYNAMIC_TEXT("dynamic-text");

        final String key;

        Feature(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    static EngineCapabilities nativeEngine() {
        return new EngineCapabilities(true, true, true, true, true, true, true, true, true);
    }

    /**
     * DecentHolograms has pages (also per viewer) and item lines. Its text is rendered by DecentHolograms
     * itself, so Atrium's per-viewer placeholders, click actions and line offsets cannot be reproduced.
     */
    static EngineCapabilities decentHolograms() {
        return new EngineCapabilities(true, true, false, false, true, false, false, true, false);
    }

    /**
     * FancyHolograms text holograms: one text block, no pages, no item or block lines. Atrium still controls
     * who sees the hologram.
     */
    static EngineCapabilities fancyHolograms() {
        return new EngineCapabilities(false, false, false, false, false, false, false, true, false);
    }

    boolean supports(Feature feature) {
        return switch (feature) {
            case PAGES -> pages;
            case PER_VIEWER_PAGES -> perViewerPages;
            case PER_VIEWER_TEXT -> perViewerText;
            case CLICK_ACTIONS -> clickActions;
            case ITEM_LINES -> itemLines;
            case BLOCK_LINES -> blockLines;
            case LINE_OFFSETS -> lineOffsets;
            case PER_VIEWER_VISIBILITY -> perViewerVisibility;
            case DYNAMIC_TEXT -> dynamicText;
        };
    }

    List<Feature> supported() {
        List<Feature> result = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (supports(feature)) {
                result.add(feature);
            }
        }
        return List.copyOf(result);
    }

    List<Feature> missing() {
        List<Feature> result = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (!supports(feature)) {
                result.add(feature);
            }
        }
        return List.copyOf(result);
    }

    /** Comma-separated stable keys, for /holo engine and logs. */
    static String describe(List<Feature> features) {
        return features.stream().map(Feature::key).reduce((a, b) -> a + ", " + b).orElse("none");
    }

    @Override
    public String toString() {
        return "supported=" + describe(supported()) + "; missing=" + describe(missing());
    }

    static String capabilityKey(Feature feature) {
        return "holograms.feature-" + feature.key.toLowerCase(Locale.ROOT);
    }
}
