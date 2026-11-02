package dev.yanianz.atrium.api.moderation;

import java.util.EnumSet;
import java.util.Set;

/** Kind of a punishment record. Every kind is stored in the same history. */
public enum PunishmentType {
    /** Permanent account ban. */
    BAN,
    /** Account ban with an expiry. */
    TEMPBAN,
    /** Ban of an IP address or CIDR range (optionally linked to the account it was taken from). */
    IPBAN,
    /** Permanent mute; the player is told they are muted. */
    MUTE,
    /** Mute with an expiry. */
    TEMPMUTE,
    /** Mute the player is not told about: their messages are shown only to themselves. */
    SHADOWMUTE,
    /** Warning; counts towards escalation rules until it expires or is removed. */
    WARN,
    /** One-off kick, kept for the history only. */
    KICK,
    /** Confinement to a jail area, optionally with an expiry. */
    JAIL,
    /** Player cannot move or interact until unfrozen. */
    FREEZE;

    public static final Set<PunishmentType> BANS = EnumSet.of(BAN, TEMPBAN);
    public static final Set<PunishmentType> MUTES = EnumSet.of(MUTE, TEMPMUTE, SHADOWMUTE);

    /**
     * Group of kinds of which a player can have only one active record at a time. Applying a new
     * punishment of a group replaces (revokes) the previous active one of the same group.
     */
    public Set<PunishmentType> group() {
        return switch (this) {
            case BAN, TEMPBAN -> BANS;
            case MUTE, TEMPMUTE, SHADOWMUTE -> MUTES;
            default -> EnumSet.of(this);
        };
    }

    /** {@code true} for kinds that never stay "active" (kicks). */
    public boolean isInstant() {
        return this == KICK;
    }
}
