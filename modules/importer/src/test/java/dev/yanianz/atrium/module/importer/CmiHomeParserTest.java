package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CmiHomeParserTest {
    private static String fixture(String name) throws Exception {
        Path path = Path.of(CmiHomeParserTest.class.getResource("/fixtures/" + name).toURI());
        return Files.readString(path);
    }

    @Test
    void parsesLegacyHomesAndReportsInvalidNames() throws Exception {
        List<CmiHomeParser.ParsedHome> homes = CmiHomeParser.parse(fixture("homes-legacy.txt"));
        assertEquals(3, homes.size());
        assertEquals("home", homes.get(0).name());
        assertTrue(homes.get(0).ok());
        assertEquals("world_nether", homes.get(1).location().world());
        assertEquals("base", homes.get(1).name());
        assertFalse(homes.get(2).ok());
        assertNull(homes.get(2).name());
    }

    @Test
    void parsesJsonHomes() throws Exception {
        List<CmiHomeParser.ParsedHome> homes = CmiHomeParser.parse(fixture("homes.json"));
        assertEquals(3, homes.size());
        assertEquals("home", homes.get(0).name());
        assertEquals("world", homes.get(0).location().world());
        assertEquals("farm", homes.get(1).name());
        assertEquals("my:world", homes.get(1).location().world());
        assertFalse(homes.get(2).ok());
    }

    @Test
    void blankAndMalformedInput() {
        assertTrue(CmiHomeParser.parse(null).isEmpty());
        assertTrue(CmiHomeParser.parse("").isEmpty());
        List<CmiHomeParser.ParsedHome> broken = CmiHomeParser.parse("home without separator");
        assertEquals(1, broken.size());
        assertFalse(broken.getFirst().ok());
        assertFalse(CmiHomeParser.parse("{ not json").getFirst().ok());
    }
}
