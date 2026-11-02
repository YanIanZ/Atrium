package dev.yanianz.atrium.module.portals;

import static org.junit.jupiter.api.Assertions.*;

import dev.yanianz.atrium.api.portals.PortalUseEvent.TargetType;
import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PortalTargetTest {
    private static final StoredLocation HERE = new StoredLocation("world", 10.25, 64, -3.5, 90f, 0f);

    @Test void parsesAdminInput() {
        assertEquals(PortalTarget.NONE, PortalTarget.parse("none", Optional::empty).orElseThrow());
        assertEquals(new PortalTarget(TargetType.WARP, "spawn"), PortalTarget.parse("warp:spawn", Optional::empty).orElseThrow());
        assertEquals(new PortalTarget(TargetType.SERVER, "lobby-1"), PortalTarget.parse("Server:lobby-1", Optional::empty).orElseThrow());
        assertEquals(TargetType.LOCATION, PortalTarget.parse("location", () -> Optional.of(HERE)).orElseThrow().type());
        assertTrue(PortalTarget.parse("location", Optional::empty).isEmpty(), "console has no location");
        assertTrue(PortalTarget.parse("warp:", Optional::empty).isEmpty());
        assertTrue(PortalTarget.parse("warp:a b", Optional::empty).isEmpty());
        assertTrue(PortalTarget.parse("warp:<red>x", Optional::empty).isEmpty());
        assertTrue(PortalTarget.parse("somewhere", Optional::empty).isEmpty());
    }

    @Test void roundTripsAndIsLocaleIndependent() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("id-ID"));
            PortalTarget loc = PortalTarget.parse("location", () -> Optional.of(HERE)).orElseThrow();
            assertFalse(loc.encode().contains(","), loc.encode());
            assertEquals(loc, PortalTarget.decode(loc.encode()));
            assertEquals(HERE, loc.location().orElseThrow());
            assertEquals("world 10.3 64.0 -3.5", loc.describe());
            for (PortalTarget t : new PortalTarget[] {PortalTarget.NONE, new PortalTarget(TargetType.WARP, "spawn"),
                    new PortalTarget(TargetType.SERVER, "hub")}) {
                assertEquals(t, PortalTarget.decode(t.encode()));
            }
        } finally {
            Locale.setDefault(old);
        }
    }

    @Test void brokenStoredTextDecodesToNone() {
        assertEquals(PortalTarget.NONE, PortalTarget.decode(null));
        assertEquals(PortalTarget.NONE, PortalTarget.decode("teleport:x"));
        assertEquals(PortalTarget.NONE, PortalTarget.decode("location:world;1,5;2;3;0;0"));
        assertEquals(PortalTarget.NONE, PortalTarget.decode("warp:bad name"));
    }

    @Test void validatesNamesAndPermissions() {
        assertTrue(Portal.validName("hub_2-north"));
        assertFalse(Portal.validName("a.b"));
        assertFalse(Portal.validName(""));
        assertFalse(Portal.validName("x".repeat(33)));
        assertTrue(Portal.validPermission("server.portal.vip"));
        assertFalse(Portal.validPermission("perm with space"));
    }
}
