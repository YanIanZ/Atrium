package dev.yanianz.atrium.module.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

class EssentialsFileParsersTest {
    private static ConfigurationNode fixture(String name) throws Exception {
        Path path = Path.of(EssentialsFileParsersTest.class.getResource("/fixtures/" + name).toURI());
        return YamlConfigurationLoader.builder().path(path).build().load();
    }

    @Test
    void parsesASingleWarpFile() throws Exception {
        List<EssentialsWarpParser.ParsedWarp> warps =
                EssentialsWarpParser.parse("spawn.yml", fixture("essentials-warp.yml"));
        assertEquals(1, warps.size());
        EssentialsWarpParser.ParsedWarp spawn = warps.getFirst();
        assertTrue(spawn.ok());
        assertEquals("spawn", spawn.name());
        assertEquals("general", spawn.category());
        assertEquals("world", spawn.location().worldToken());
        assertEquals(0.5, spawn.location().x());
        assertEquals(180.0f, spawn.location().yaw());
    }

    @Test
    void parsesACombinedWarpsSectionAndReportsBrokenEntries() throws Exception {
        List<EssentialsWarpParser.ParsedWarp> warps =
                EssentialsWarpParser.parse("combined.yml", fixture("essentials-warps.yml"));
        assertEquals(2, warps.size());
        assertEquals("shop", warps.get(0).name());
        assertTrue(warps.get(0).ok());
        assertEquals(-10.0, warps.get(0).location().z());
        assertEquals("bad", warps.get(1).name());
        assertFalse(warps.get(1).ok());
    }

    @Test
    void prefersTheDefaultSpawnGroup() throws Exception {
        List<EssentialsSpawnParser.SpawnPoint> points =
                EssentialsSpawnParser.parse(fixture("essentials-spawn.yml"));
        assertEquals(2, points.size());
        EssentialsSpawnParser.SpawnPoint preferred = EssentialsSpawnParser.preferred(points);
        assertNotNull(preferred);
        assertEquals("default", preferred.group());
        assertEquals(0.0, preferred.location().x());
    }

    @Test
    void resolvesHomeWorldsByUuidOrName() {
        UUID worldId = UUID.fromString("5f9d6a1e-1111-2222-3333-444455556666");
        EssentialsLocations.WorldIndex index = new EssentialsLocations.WorldIndex(
                java.util.Map.of(worldId, "world_nether"), java.util.Set.of("world", "world_nether"));

        EssentialsLocations.EssLocation byUuid = new EssentialsLocations.EssLocation(
                worldId.toString(), null, 1, 2, 3, 0f, 0f);
        assertEquals("world_nether", index.resolve(byUuid).orElseThrow().world());

        EssentialsLocations.EssLocation byName = new EssentialsLocations.EssLocation(
                "world", null, 1, 2, 3, 0f, 0f);
        assertEquals("world", index.resolve(byName).orElseThrow().world());

        // Regression: a stale world-name used to win over the world UUID.
        EssentialsLocations.EssLocation uuidWins = new EssentialsLocations.EssLocation(
                worldId.toString(), "world", 1, 2, 3, 0f, 0f);
        assertEquals("world_nether", index.resolve(uuidWins).orElseThrow().world());

        EssentialsLocations.EssLocation nameFallback = new EssentialsLocations.EssLocation(
                UUID.randomUUID().toString(), "world", 1, 2, 3, 0f, 0f);
        assertEquals("world", index.resolve(nameFallback).orElseThrow().world());

        EssentialsLocations.EssLocation unknown = new EssentialsLocations.EssLocation(
                UUID.randomUUID().toString(), null, 1, 2, 3, 0f, 0f);
        assertTrue(index.resolve(unknown).isEmpty(), "an unloaded world UUID has no safe name to store");
    }

    @Test
    void countsKitItemsAndDelay() throws Exception {
        List<EssentialsKitsParser.ParsedKit> kits = EssentialsKitsParser.parse(fixture("essentials-kits.yml"));
        assertEquals(2, kits.size());
        EssentialsKitsParser.ParsedKit starter = kits.get(0);
        assertTrue(starter.ok());
        assertEquals("starter", starter.name());
        assertEquals(2, starter.items());
        assertEquals(600, starter.delaySeconds());
        EssentialsKitsParser.ParsedKit vip = kits.get(1);
        assertEquals(1, vip.items());
        assertEquals("100", vip.money());
    }
}
