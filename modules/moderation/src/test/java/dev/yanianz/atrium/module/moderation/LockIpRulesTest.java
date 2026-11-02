package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LockIpRulesTest {
    @Test
    void noLockAllowsEveryAddress() {
        assertTrue(LockIpRules.allows(List.of(), "1.2.3.4"));
        assertTrue(LockIpRules.allows(List.of(), "2001:db8::1"));
        assertFalse(LockIpRules.restricted(List.of()));
    }

    @Test
    void ipv4MatchesRegardlessOfSurroundingText() {
        assertTrue(LockIpRules.allows(List.of("10.0.0.1"), " 10.0.0.1 "));
        assertFalse(LockIpRules.allows(List.of("10.0.0.1"), "10.0.0.2"));
    }

    @Test
    void ipv4MappedAddressMatchesIpv4Lock() {
        assertTrue(LockIpRules.allows(List.of("1.2.3.4"), "::ffff:1.2.3.4"));
        assertTrue(LockIpRules.allows(List.of("::ffff:1.2.3.4"), "1.2.3.4"));
    }

    @Test
    void ipv6IsComparedAfterNormalisation() {
        assertTrue(LockIpRules.allows(List.of("2001:db8::1"), "2001:0DB8:0:0:0:0:0:1"));
        assertTrue(LockIpRules.allows(List.of("2001:0db8::0001"), "2001:db8::1"));
        assertFalse(LockIpRules.allows(List.of("2001:db8::1"), "2001:db8::2"));
        assertFalse(LockIpRules.allows(List.of("2001:db8::/64"), "2001:db8::1"),
                "a lock is an exact address, never a prefix");
    }

    @Test
    void anyListedAddressIsEnough() {
        List<String> allowed = List.of("10.0.0.1", "192.168.1.20", "2001:db8::5");
        assertTrue(LockIpRules.allows(allowed, "192.168.1.20"));
        assertTrue(LockIpRules.allows(allowed, "2001:db8::5"));
        assertFalse(LockIpRules.allows(allowed, "192.168.1.21"));
    }

    @Test
    void unknownAddressIsRefusedWhenLocked() {
        assertFalse(LockIpRules.allows(List.of("10.0.0.1"), null));
        assertFalse(LockIpRules.allows(List.of("10.0.0.1"), ""));
        assertFalse(LockIpRules.allows(List.of("10.0.0.1"), "example.com"));
        assertTrue(LockIpRules.allows(List.of(), null), "no lock never blocks, even an unknown address");
    }

    @Test
    void familiesNeverMix() {
        assertFalse(LockIpRules.allows(List.of("2001:db8::1"), "10.0.0.1"));
        assertFalse(LockIpRules.allows(List.of("10.0.0.1"), "2001:db8::1"));
    }
}
