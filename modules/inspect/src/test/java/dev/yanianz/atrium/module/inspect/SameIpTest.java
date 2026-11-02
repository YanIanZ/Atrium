package dev.yanianz.atrium.module.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SameIpTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void onlyGroupsWithTwoAccountsAreReturned() {
        List<SameIp.Group> groups = SameIp.group(List.of(
                new SameIp.Session(A, "Alice", "203.0.113.5"),
                new SameIp.Session(B, "Bob", "203.0.113.5"),
                new SameIp.Session(C, "Carol", "198.51.100.9")));
        assertEquals(1, groups.size());
        assertEquals("203.0.113.5", groups.getFirst().address());
        assertEquals(2, groups.getFirst().sessions().size());
    }

    @Test
    void loopbackAndBlankAddressesAreIgnored() {
        assertTrue(SameIp.group(List.of(
                new SameIp.Session(A, "Alice", "127.0.0.1"),
                new SameIp.Session(B, "Bob", "127.0.0.1"),
                new SameIp.Session(C, "Carol", ""))).isEmpty());
    }

    @Test
    void groupsAreOrderedBySizeThenAddressAndNamesSorted() {
        List<SameIp.Group> groups = SameIp.group(List.of(
                new SameIp.Session(A, "Zoe", "198.51.100.1"),
                new SameIp.Session(B, "Amy", "198.51.100.1"),
                new SameIp.Session(C, "Cara", "198.51.100.2"),
                new SameIp.Session(A, "Alice", "203.0.113.7"),
                new SameIp.Session(B, "Bob", "203.0.113.7"),
                new SameIp.Session(C, "Carol", "203.0.113.7")));
        assertEquals(2, groups.size());
        assertEquals("203.0.113.7", groups.getFirst().address());
        assertEquals("Alice", groups.getFirst().sessions().getFirst().name());
        assertEquals("198.51.100.1", groups.get(1).address());
    }

    @Test
    void maskingKeepsOnlyTheFirstOctet() {
        assertEquals("203.*.*.*", InspectFormat.maskAddress("203.0.113.5"));
        assertEquals("2001:\u2026", InspectFormat.maskAddress("2001:db8::1"));
        assertEquals("hidden", InspectFormat.maskAddress("nonsense"));
        assertEquals("hidden", InspectFormat.maskAddress(null));
    }
}
