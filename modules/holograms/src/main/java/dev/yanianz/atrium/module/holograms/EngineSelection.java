package dev.yanianz.atrium.module.holograms;

/**
 * Pure engine choice. Kept separate from Bukkit so the fallback order can be tested without a server.
 *
 * <p>{@code auto} prefers DecentHolograms, then FancyHolograms, then the native display-entity engine.
 * On Folia an old DecentHolograms (before 2.10, when Folia support was added) is skipped. An explicitly
 * configured engine that is unavailable falls back to native with a reason the caller logs once.
 */
final class EngineSelection {
    record Selection(EngineKind engine, boolean fallback, String reason) {
        static Selection direct(EngineKind engine) {
            return new Selection(engine, false, "");
        }

        static Selection fallback(String reason) {
            return new Selection(EngineKind.NATIVE, true, reason);
        }
    }

    static final String PLUGIN_MISSING = "plugin-missing";
    static final String FOLIA_UNSUPPORTED = "folia-unsupported";
    /** The active external engine's plugin was disabled at runtime; Atrium switched to native. */
    static final String PLUGIN_DISABLED = "plugin-disabled";

    private EngineSelection() {}

    /** The engine backed by a plugin, by its plugin name; empty for any other plugin. */
    static java.util.Optional<EngineKind> kindOfPlugin(String pluginName) {
        if ("DecentHolograms".equals(pluginName)) {
            return java.util.Optional.of(EngineKind.DECENTHOLOGRAMS);
        }
        if ("FancyHolograms".equals(pluginName)) {
            return java.util.Optional.of(EngineKind.FANCYHOLOGRAMS);
        }
        return java.util.Optional.empty();
    }

    /** A plugin was disabled: true when it backs the active engine, which must then fall back to native. */
    static boolean fallBackOnDisable(EngineKind active, String disabledPlugin) {
        return active != EngineKind.NATIVE && kindOfPlugin(disabledPlugin).orElse(null) == active;
    }

    /**
     * A plugin was enabled: true when Atrium runs native only because that plugin was missing or disabled and
     * the configuration now selects it ({@code selected} is the selection for the configured value).
     */
    static boolean returnOnEnable(EngineKind active, EngineKind configured, String fallbackReason,
            String enabledPlugin, EngineKind selected) {
        EngineKind enabled = kindOfPlugin(enabledPlugin).orElse(null);
        if (enabled == null || active != EngineKind.NATIVE || selected != enabled) {
            return false;
        }
        return configured == enabled || PLUGIN_DISABLED.equals(fallbackReason);
    }

    static Selection select(EngineKind requested, boolean decentAvailable, boolean fancyAvailable, boolean folia,
            boolean decentFoliaReady) {
        EngineKind wanted = requested == null ? EngineKind.AUTO : requested;
        return switch (wanted) {
            case NATIVE -> Selection.direct(EngineKind.NATIVE);
            case AUTO -> {
                if (decentAvailable && (!folia || decentFoliaReady)) {
                    yield Selection.direct(EngineKind.DECENTHOLOGRAMS);
                }
                if (fancyAvailable) {
                    yield Selection.direct(EngineKind.FANCYHOLOGRAMS);
                }
                yield Selection.direct(EngineKind.NATIVE);
            }
            case DECENTHOLOGRAMS -> {
                if (!decentAvailable) {
                    yield Selection.fallback(PLUGIN_MISSING);
                }
                if (folia && !decentFoliaReady) {
                    yield Selection.fallback(FOLIA_UNSUPPORTED);
                }
                yield Selection.direct(EngineKind.DECENTHOLOGRAMS);
            }
            case FANCYHOLOGRAMS -> fancyAvailable
                    ? Selection.direct(EngineKind.FANCYHOLOGRAMS)
                    : Selection.fallback(PLUGIN_MISSING);
        };
    }
}
