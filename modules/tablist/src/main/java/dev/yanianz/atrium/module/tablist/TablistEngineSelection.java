package dev.yanianz.atrium.module.tablist;

/**
 * Pure engine choice, kept free of Bukkit so the fallback rules can be tested without a server.
 *
 * <p>{@code auto} uses TAB when the TAB plugin is enabled, otherwise native. An explicit {@code tab} without the
 * plugin falls back to native with a reason the caller logs once.
 */
final class TablistEngineSelection {
    /** Plugin name of TAB by NEZNAMY. */
    static final String TAB_PLUGIN = "TAB";

    static final String PLUGIN_MISSING = "plugin-missing";
    /** TAB was disabled while the server was running; Atrium switched to native. */
    static final String PLUGIN_DISABLED = "plugin-disabled";
    /** The TAB API could not be used although the plugin is enabled. */
    static final String START_FAILED = "start-failed";

    record Selection(TablistEngine engine, boolean fallback, String reason) {
        static Selection direct(TablistEngine engine) {
            return new Selection(engine, false, "");
        }

        static Selection fallback(String reason) {
            return new Selection(TablistEngine.NATIVE, true, reason);
        }
    }

    private TablistEngineSelection() {}

    static Selection select(TablistEngine requested, boolean tabAvailable) {
        TablistEngine wanted = requested == null ? TablistEngine.AUTO : requested;
        return switch (wanted) {
            case NATIVE -> Selection.direct(TablistEngine.NATIVE);
            case AUTO -> Selection.direct(tabAvailable ? TablistEngine.TAB : TablistEngine.NATIVE);
            case TAB -> tabAvailable ? Selection.direct(TablistEngine.TAB) : Selection.fallback(PLUGIN_MISSING);
        };
    }

    /** A plugin was disabled: true when it is TAB and TAB is the active engine. */
    static boolean fallBackOnDisable(TablistEngine active, String disabledPlugin) {
        return active == TablistEngine.TAB && TAB_PLUGIN.equals(disabledPlugin);
    }

    /**
     * A plugin was enabled: true when it is TAB, Atrium runs native, and either the configuration asks for
     * {@code tab} explicitly or Atrium only left TAB because the plugin was disabled at runtime. {@code auto} on a
     * server that started without TAB does not switch by surprise.
     */
    static boolean returnOnEnable(TablistEngine active, TablistEngine configured, String fallbackReason,
            String enabledPlugin) {
        if (!TAB_PLUGIN.equals(enabledPlugin) || active != TablistEngine.NATIVE
                || configured == TablistEngine.NATIVE) {
            return false;
        }
        return configured == TablistEngine.TAB || PLUGIN_DISABLED.equals(fallbackReason);
    }
}
