package dev.yanianz.atrium.api.afk;

/** Why a player entered or left AFK. */
public enum AfkReason {
    /** The idle timer expired and the module marked the player AFK. */
    AUTOMATIC,
    /** The player used {@code /afk}. */
    MANUAL,
    /** The player did something that counts as activity. */
    ACTIVITY,
    /** Another plugin changed the state through {@link AfkService#setAfk}. */
    PLUGIN
}
