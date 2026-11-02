package dev.yanianz.atrium.module.scoreboard;

/**
 * Pure decisions about the client-side sidebar objective, kept out of the packet backend so they can be
 * unit-tested. The client keeps an objective until it receives an explicit REMOVE, so what matters on a
 * hide is whether a CREATE actually reached the client, not whether the server still remembers any state.
 */
final class SidebarVisibility {
    private SidebarVisibility() {}

    /**
     * Whether a hide must send the objective REMOVE packet. Sending it for a player who never received a
     * create is pointless on every cycle, and a packet for an offline player cannot be delivered.
     */
    static boolean needsRemoval(boolean created, boolean online) {
        return created && online;
    }
}
