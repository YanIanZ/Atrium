package dev.yanianz.atrium.module.homes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.teleport.StoredLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class HomesTest {
    private static Home home(String name, String world) {
        return new Home(name, new StoredLocation(world, 1, 64, 1, 0, 0), 0L);
    }

    @Test
    void caseInsensitiveAndSorted() {
        Homes homes = Homes.EMPTY.with(home("Zeta", "world")).with(home("alpha", "world"));
        assertTrue(homes.contains("ZETA"));
        assertEquals(List.of("alpha", "Zeta"), homes.list().stream().map(Home::name).toList());
        Homes replaced = homes.with(home("zeta", "nether"));
        assertEquals(2, replaced.count());
        assertEquals("nether", replaced.get("Zeta").orElseThrow().location().world());
    }

    @Test
    void immutable() {
        Homes homes = Homes.EMPTY.with(home("a", "world"));
        assertEquals(0, Homes.EMPTY.count());
        assertThrows(UnsupportedOperationException.class, () -> homes.byName().clear());
        assertSame(homes, homes.without("missing"));
        assertEquals(0, homes.without("A").count());
        assertEquals(1, homes.count());
    }

    @Test
    void limitCountsEveryHomeButAllowsReplacing() {
        // Homes in a world that is not loaded still count (no limit bypass by unloading worlds).
        Homes homes = Homes.of(List.of(home("a", "world"), home("b", "unloaded_world")));
        assertFalse(homes.canSet("c", 2));
        assertTrue(homes.canSet("B", 2));
        assertTrue(homes.canSet("c", 3));
        assertFalse(Homes.EMPTY.canSet("a", 0));
    }

    @Test
    void validNames() {
        assertTrue(Homes.validName("base_1-x"));
        assertFalse(Homes.validName(""));
        assertFalse(Homes.validName("a b"));
        assertFalse(Homes.validName("x:y"));
        assertFalse(Homes.validName("<red>"));
        assertFalse(Homes.validName("a".repeat(33)));
        assertFalse(Homes.validName(null));
    }
}
