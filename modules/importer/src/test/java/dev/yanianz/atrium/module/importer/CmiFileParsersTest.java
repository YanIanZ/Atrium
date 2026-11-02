package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class CmiFileParsersTest {
    private static ConfigurationNode fixture(String name) throws Exception {
        Path path = Path.of(CmiFileParsersTest.class.getResource("/fixtures/" + name).toURI());
        return YamlConfigurationLoader.builder().path(path).build().load();
    }

    @Test
    void parsesWarpsFile() throws Exception {
        List<CmiWarpFileParser.ParsedWarp> warps = CmiWarpFileParser.parse(fixture("warps.yml"));
        assertEquals(3, warps.size());
        assertEquals("spawn", warps.get(0).name());
        assertTrue(warps.get(0).ok());
        assertEquals("general", warps.get(0).category());
        assertEquals("shop", warps.get(1).name());
        assertEquals(10, warps.get(1).location().x());
        assertEquals("broken", warps.get(2).name());
        assertFalse(warps.get(2).ok());
    }

    @Test
    void parsesHologramsWithPagesAndLegacyColours() throws Exception {
        List<CmiHologramFileParser.ParsedHologram> holograms = CmiHologramFileParser.parse(fixture("holograms.yml"));
        assertEquals(3, holograms.size());
        CmiHologramFileParser.ParsedHologram spawn = holograms.get(0);
        assertEquals("SpawnInfo", spawn.name());
        assertTrue(spawn.ok());
        assertEquals(2, spawn.pages().size());
        assertEquals("<gold>Welcome", spawn.pages().get(0).getFirst());
        assertEquals("<green>Page two", spawn.pages().get(1).getFirst());
        assertFalse(holograms.get(1).enabled());
        assertFalse(holograms.get(2).ok());
    }
}
