package dev.yanianz.atrium.module.nick;

/**
 * Persisted nickname of one player.
 *
 * @param nick      the MiniMessage source as typed, without the display prefix
 * @param plain     the visible text with every tag removed and colour stripped
 * @param updatedAt epoch milliseconds of the last change
 */
public record NickState(String nick, String plain, long updatedAt) {
    public static final NickState EMPTY = new NickState(null, "", 0L);

    public NickState {
        plain = plain == null ? "" : plain;
        if (nick != null && nick.isBlank()) {
            nick = null;
        }
    }

    public boolean hasNick() {
        return nick != null;
    }

    public static NickState of(String nick, String plain) {
        return new NickState(nick, plain, System.currentTimeMillis());
    }
}
