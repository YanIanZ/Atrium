package dev.yanianz.atrium.module.warps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.core.config.ConfigService;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/** Icons are stored as a material name + custom model data only, never as full item NBT. */
class WarpsConfigTest {
    @TempDir
    Path dir;

    private WarpsConfig roundTrip(WarpsConfig config) throws Exception {
        Path file = dir.resolve("warps.yml");
        YamlConfigurationLoader loader = ConfigService.loader(file);
        CommentedConfigurationNode node = loader.load();
        node.set(WarpsConfig.class, config);
        loader.save(node);
        return ConfigService.loader(file).load().get(WarpsConfig.class);
    }

    @Test
    void iconRoundTripKeepsMaterialAndCustomModelData() throws Exception {
        WarpsConfig config = new WarpsConfig();
        config.icons.put("Shop", new WarpsConfig.Icon("COMPASS", 42));
        config.icons.put("plain", new WarpsConfig.Icon("CHEST", -1));

        WarpsConfig read = roundTrip(config);
        assertEquals("COMPASS", read.icon("shop").material);
        assertEquals(42, read.icon("SHOP").customModelData);
        assertEquals(-1, read.icon("plain").customModelData);
        assertNull(read.icon("missing"));
    }

    @Test
    void anEmptyFileGetsTheDefaults() throws Exception {
        Path file = dir.resolve("empty.yml");
        assertTrue(file.toFile().createNewFile());
        WarpsConfig read = new ConfigService().open(file, WarpsConfig.class, WarpsConfig::new).get();
        assertTrue(read.guiEnabled);
        assertEquals(6, read.guiRows);
        assertEquals(2, read.defaultPlayerWarpLimit);
        assertEquals("CHEST", read.defaultIcon);
        assertTrue(read.icons.isEmpty());
    }
}
