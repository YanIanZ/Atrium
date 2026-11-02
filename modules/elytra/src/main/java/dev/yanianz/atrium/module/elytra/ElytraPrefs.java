package dev.yanianz.atrium.module.elytra;

/** Per-player switches for the elytra features. Immutable. */
record ElytraPrefs(boolean boost, boolean launch) {
    static final ElytraPrefs DEFAULT = new ElytraPrefs(true, true);

    ElytraPrefs withBoost(boolean value) {
        return new ElytraPrefs(value, launch);
    }

    ElytraPrefs withLaunch(boolean value) {
        return new ElytraPrefs(boost, value);
    }
}
