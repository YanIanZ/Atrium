package dev.yanianz.atrium.api;

import java.util.Objects;

/** Static entry point for other plugins. Available after Atrium has enabled. */
public final class Atrium {
    private static volatile AtriumApi api;

    private Atrium() {}

    public static AtriumApi api() {
        AtriumApi current = api;
        if (current == null) {
            throw new IllegalStateException("Atrium is not enabled yet");
        }
        return current;
    }

    public static boolean isAvailable() {
        return api != null;
    }

    /** Internal: called by the Atrium plugin on enable/disable. */
    public static void install(AtriumApi instance) {
        api = instance;
    }

    public static void uninstall(AtriumApi instance) {
        if (Objects.equals(api, instance)) {
            api = null;
        }
    }
}
