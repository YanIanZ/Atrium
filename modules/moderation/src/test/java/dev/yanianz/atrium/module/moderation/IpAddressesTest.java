package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IpAddressesTest {
    @Test
    void ipv4IsStrictDottedDecimal() {
        assertEquals(Optional.of("10.0.0.1"), IpAddresses.normalize(" 10.0.0.1 "));
        assertEquals(Optional.of("255.255.255.255"), IpAddresses.normalize("255.255.255.255"));
        assertTrue(IpAddresses.normalize("256.1.1.1").isEmpty());
        assertTrue(IpAddresses.normalize("010.0.0.1").isEmpty(), "leading zeros are ambiguous (octal)");
        assertTrue(IpAddresses.normalize("10.0.1").isEmpty());
        assertTrue(IpAddresses.normalize("1.2.3.4.5").isEmpty());
        assertTrue(IpAddresses.normalize("example.com").isEmpty(), "host names are never resolved");
        assertTrue(IpAddresses.normalize("Notch").isEmpty());
        assertTrue(IpAddresses.normalize("").isEmpty());
    }

    @Test
    void ipv6FollowsRfc5952() {
        assertEquals(Optional.of("2001:db8::1"), IpAddresses.normalize("2001:DB8:0:0:0:0:0:1"));
        assertEquals(Optional.of("2001:db8::1"), IpAddresses.normalize("2001:0db8::0001"));
        assertEquals(Optional.of("2001:db8::1:0:0:1"), IpAddresses.normalize("2001:db8:0:0:1:0:0:1"),
                "first of two equally long zero runs is compressed");
        assertEquals(Optional.of("2001:0:0:1::1"), IpAddresses.normalize("2001:0:0:1:0:0:0:1"),
                "longest zero run is compressed");
        assertEquals(Optional.of("1:0:1:0:1:0:1:0"), IpAddresses.normalize("1:0:1:0:1:0:1:0"),
                "single zero groups are not compressed");
        assertEquals(Optional.of("::"), IpAddresses.normalize("0:0:0:0:0:0:0:0"));
        assertEquals(Optional.of("::1"), IpAddresses.normalize("[::1]"));
        assertEquals(Optional.of("fe80::1"), IpAddresses.normalize("fe80::1%eth0"));
        assertEquals(Optional.of("1::"), IpAddresses.normalize("1:0:0:0:0:0:0:0"));
        assertTrue(IpAddresses.normalize("2001:db8::g").isEmpty());
        assertTrue(IpAddresses.normalize("1::2::3").isEmpty());
    }

    @Test
    void ipv4MappedBecomesIpv4() {
        assertEquals(Optional.of("1.2.3.4"), IpAddresses.normalize("::ffff:1.2.3.4"));
        assertEquals(Optional.of("1.2.3.4"), IpAddresses.normalize("::FFFF:0102:0304"));
        assertTrue(IpAddresses.normalize("::ffff:1.2.3.04").isEmpty());
    }

    @Test
    void normalizesInetAddress() throws Exception {
        assertEquals(Optional.of("2001:db8::5"), IpAddresses.normalize(InetAddress.getByAddress(
                new byte[] {0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5})));
        assertEquals(Optional.of("127.0.0.1"), IpAddresses.normalize(InetAddress.getByAddress(new byte[] {127, 0, 0, 1})));
    }

    @Test
    void rangesClearHostBits() {
        assertEquals(Optional.of("10.0.0.0/8"), IpAddresses.normalizeRange("10.1.2.3/8"));
        assertEquals(Optional.of("2001:db8::/64"), IpAddresses.normalizeRange("2001:DB8:0:0:abcd::1/64"));
        assertEquals(Optional.of("1.2.3.4"), IpAddresses.normalizeRange("1.2.3.4/32"));
        assertEquals(Optional.of("0.0.0.0/0"), IpAddresses.normalizeRange("9.9.9.9/0"));
        assertEquals(Optional.of("1.2.3.0/24"), IpAddresses.normalizeRange("::ffff:1.2.3.4/120"));
        assertTrue(IpAddresses.normalizeRange("1.2.3.4/33").isEmpty());
        assertTrue(IpAddresses.normalizeRange("::1/129").isEmpty());
        assertTrue(IpAddresses.normalizeRange("1.2.3.4/").isEmpty());
        assertTrue(IpAddresses.normalizeRange("1.2.3.4/-1").isEmpty());
        assertTrue(IpAddresses.isAddressOrRange("2001:db8::/32"));
        assertFalse(IpAddresses.isAddressOrRange("Notch"));
    }

    @Test
    void matchesAddressesAndRanges() {
        assertTrue(IpAddresses.matches("2001:db8::1", "2001:0DB8:0:0:0:0:0:1"), "different spelling, same address");
        assertTrue(IpAddresses.matches("10.0.0.0/8", "10.200.1.1"));
        assertFalse(IpAddresses.matches("10.0.0.0/8", "11.0.0.1"));
        assertTrue(IpAddresses.matches("2001:db8:1:2::/64", "2001:db8:1:2:aaaa:bbbb:cccc:dddd"),
                "IPv6 privacy addresses of one /64 are caught by a /64 ban");
        assertFalse(IpAddresses.matches("2001:db8:1:2::/64", "2001:db8:1:3::1"));
        assertTrue(IpAddresses.matches("1.2.3.4", "::ffff:1.2.3.4"), "dual-stack mapped client matches IPv4 ban");
        assertFalse(IpAddresses.matches("::/0", "1.2.3.4"), "families never mix");
        assertFalse(IpAddresses.matches("1.2.3.4", "garbage"));
        assertTrue(IpAddresses.matches("0.0.0.0/0", "8.8.8.8"));
    }

    @Test
    void masksForPublicMessages() {
        assertEquals("1.2.*.*", IpAddresses.mask("1.2.3.4"));
        assertEquals("10.0.*.*", IpAddresses.mask("10.0.0.0/8"));
        assertEquals("2001:db8:*", IpAddresses.mask("2001:db8::1"));
    }
}
