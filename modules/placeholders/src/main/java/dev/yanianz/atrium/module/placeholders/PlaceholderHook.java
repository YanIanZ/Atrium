package dev.yanianz.atrium.module.placeholders;

/** Registered only when PlaceholderAPI is enabled; keeps PlaceholderAPI types out of the module class. */
interface PlaceholderHook {
    /** Registers the expansion. Returns {@code false} when PlaceholderAPI refused it. */
    boolean register();

    /** Unregisters the expansion again (module disable). */
    boolean unregister();
}
