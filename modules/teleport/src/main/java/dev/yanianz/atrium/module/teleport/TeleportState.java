package dev.yanianz.atrium.module.teleport;

/**
 * Persisted per-player teleport state. Immutable; change with the {@code with*} methods.
 *
 * @param back            /back history, newest first
 * @param acceptsRequests false after /tptoggle: /tpa, /tpahere and /tp to this player are refused
 */
public record TeleportState(BackHistory back, boolean acceptsRequests) {
    public static final TeleportState DEFAULT = new TeleportState(BackHistory.EMPTY, true);

    public TeleportState {
        if (back == null) {
            back = BackHistory.EMPTY;
        }
    }

    public TeleportState withBack(BackHistory history) {
        return new TeleportState(history, acceptsRequests);
    }

    public TeleportState withAcceptsRequests(boolean value) {
        return new TeleportState(back, value);
    }
}
