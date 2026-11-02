package dev.yanianz.atrium.module.moderation;

import java.util.Collection;
import java.util.Optional;

/**
 * Login rule of {@code /lockip}: an account with at least one allowed address may only log in from one of
 * them. Addresses are compared after {@link IpAddresses#normalize(String) normalisation}, so the same client
 * matches whether it is written as IPv4 or as an IPv4-mapped IPv6 address, and an IPv6 privacy address only
 * matches its full, normalised form (never a prefix).
 *
 * <p>An empty list means "no restriction". When the list is not empty, an address that cannot be parsed is
 * refused as well: a lock must fail closed.
 */
final class LockIpRules {
    private LockIpRules() {}

    /** {@code true} when {@code address} may use the account. */
    static boolean allows(Collection<String> allowed, String address) {
        if (allowed.isEmpty()) {
            return true;
        }
        Optional<String> normalized = IpAddresses.normalize(address);
        if (normalized.isEmpty()) {
            return false;
        }
        String candidate = normalized.get();
        for (String entry : allowed) {
            Optional<String> locked = IpAddresses.normalize(entry);
            if (locked.isPresent() && locked.get().equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    static boolean restricted(Collection<String> allowed) {
        return !allowed.isEmpty();
    }
}
