package dev.yanianz.atrium.module.player;

/**
 * Last name seen for one account. Keyed by UUID, so a Minecraft name change never moves or drops data.
 *
 * @param lastName  the name stored on the previous join, or an empty string before the first check
 * @param changedAt epoch milliseconds of the last detected change
 */
public record PlayerIdentity(String lastName, long changedAt) {
    public static final PlayerIdentity EMPTY = new PlayerIdentity("", 0L);

    public PlayerIdentity {
        lastName = lastName == null ? "" : lastName;
    }

    public boolean hasName() {
        return !lastName.isEmpty();
    }

    public PlayerIdentity withName(String name, long at) {
        return new PlayerIdentity(name, at);
    }
}
