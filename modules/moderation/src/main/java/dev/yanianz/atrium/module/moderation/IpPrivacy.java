package dev.yanianz.atrium.module.moderation;

import org.jspecify.annotations.Nullable;

/**
 * Decides which form of an address goes into a message. Public announcements (and anything a player without
 * the staff node can read) show the masked form; staff see the exact address. A server can switch masking
 * off, in which case the exact address is always shown.
 */
final class IpPrivacy {
    private IpPrivacy() {}

    static String display(@Nullable String addressOrRange, boolean maySeeFull, boolean maskingEnabled) {
        if (addressOrRange == null || addressOrRange.isBlank()) {
            return "";
        }
        if (maySeeFull || !maskingEnabled) {
            return addressOrRange;
        }
        return IpAddresses.mask(addressOrRange);
    }
}
