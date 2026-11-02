package dev.yanianz.atrium.module.moderation;

import dev.yanianz.atrium.api.moderation.Punishment;
import dev.yanianz.atrium.api.moderation.PunishmentType;
import java.time.Instant;

/** Pure checks around IP bans, so login checks and commands use exactly the same rules. */
final class IpBanRules {
    private IpBanRules() {}

    /** Whether {@code address} (already normalised) is covered by an IP ban that is in effect at {@code now}. */
    static boolean matches(Punishment punishment, String address, Instant now) {
        return punishment.type() == PunishmentType.IPBAN
                && punishment.ip() != null
                && punishment.isActive(now)
                && IpAddresses.matches(punishment.ip(), address);
    }
}
