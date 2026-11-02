package dev.yanianz.atrium.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigServiceTest {
    @TempDir
    Path dir;

    @Test
    void writesDefaultsWithCommentsAndKeepsUserValues() throws Exception {
        Path file = dir.resolve("config.yml");
        ConfigHandle<CoreConfig> handle = new ConfigService().open(file, CoreConfig.class, CoreConfig::new);
        String text = Files.readString(file);
        assertTrue(text.contains("# Locale used for the console"), text);
        assertTrue(text.contains("default-locale: en_US"), text);
        assertTrue(text.contains("  # SQLITE, MYSQL or MARIADB"), text);

        Files.writeString(file, text.replace("default-locale: en_US", "default-locale: id_ID"));
        assertEquals("id_ID", handle.reload().defaultLocale);
        String again = Files.readString(file);
        assertEquals(1, again.split("# Locale used for the console", -1).length - 1, "comment must not duplicate");
    }

    @org.spongepowered.configurate.objectmapping.ConfigSerializable
    public static class WorldSettings {
        @org.spongepowered.configurate.objectmapping.meta.Comment("Maximum radius in blocks.")
        public int radius = 100;
    }

    @org.spongepowered.configurate.objectmapping.ConfigSerializable
    public static class WithMap {
        @org.spongepowered.configurate.objectmapping.meta.Comment("Per world settings.")
        public java.util.Map<String, WorldSettings> worlds = new java.util.LinkedHashMap<>(
                java.util.Map.of("world", new WorldSettings()));
    }

    @Test
    void commentsInsideMapValues() throws Exception {
        Path file = dir.resolve("map.yml");
        new ConfigService().open(file, WithMap.class, WithMap::new);
        String text = Files.readString(file);
        assertTrue(text.contains("# Per world settings."), text);
        assertTrue(text.contains("    # Maximum radius in blocks.\n    radius: 100"), text);
    }
}
