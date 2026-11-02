package dev.yanianz.atrium.module.staff;

/** Persisted per-player staff switches. Immutable. */
public record StaffPreferences(boolean commandSpy) {
    public static final StaffPreferences DEFAULT = new StaffPreferences(false);

    public StaffPreferences withCommandSpy(boolean enabled) {
        return new StaffPreferences(enabled);
    }
}
