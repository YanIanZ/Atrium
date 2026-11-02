package dev.yanianz.atrium.api.chat;

/** Where a player is trying to speak. Passed to {@link ChatGate}. */
public enum ChatChannel {
    CHAT,
    PRIVATE_MESSAGE,
    ME,
    STAFF_CHAT,
    BROADCAST
}
