package dev.yanianz.atrium.core.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class AddressTest {
    @Test
    void normalisesMappedAndScopedAddresses() throws Exception {
        assertEquals("1.2.3.4", UserManager.normaliseAddress(InetAddress.getByName("1.2.3.4")));
        assertEquals("1.2.3.4", UserManager.normaliseAddress(InetAddress.getByAddress(
                new byte[] {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff, 1, 2, 3, 4})));
        assertEquals("2001:db8:0:0:0:0:0:1", UserManager.normaliseAddress(InetAddress.getByName("2001:DB8::1")));
    }
}
