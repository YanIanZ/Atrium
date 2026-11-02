package dev.yanianz.atrium.module.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IpPrivacyTest {
    @Test
    void publicMessagesShowTheMaskedForm() {
        assertEquals("1.2.*.*", IpPrivacy.display("1.2.3.4", false, true));
        assertEquals("10.0.*.*", IpPrivacy.display("10.0.0.0/8", false, true));
        assertEquals("2001:db8:*", IpPrivacy.display("2001:db8::1", false, true));
    }

    @Test
    void staffSeeTheExactAddress() {
        assertEquals("1.2.3.4", IpPrivacy.display("1.2.3.4", true, true));
        assertEquals("10.0.0.0/8", IpPrivacy.display("10.0.0.0/8", true, true));
    }

    @Test
    void aServerCanSwitchMaskingOff() {
        assertEquals("1.2.3.4", IpPrivacy.display("1.2.3.4", false, false));
    }

    @Test
    void anUnknownAddressRendersEmpty() {
        assertEquals("", IpPrivacy.display(null, false, true));
        assertEquals("", IpPrivacy.display("  ", false, true));
    }
}
