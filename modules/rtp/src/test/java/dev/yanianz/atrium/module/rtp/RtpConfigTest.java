package dev.yanianz.atrium.module.rtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

class RtpConfigTest {
    @Test
    void manualSectionGetsSafeDefaults() {
        // A section written by hand only with a radius must still avoid lava, water, magma and cactus.
        RtpConfig.WorldSettings manual = new RtpConfig.WorldSettings();
        assertTrue(manual.avoidWater);
        assertTrue(manual.avoidBlocks.containsAll(java.util.List.of("LAVA", "MAGMA_BLOCK", "CACTUS", "POWDER_SNOW")));
        assertEquals(Sampler.Shape.CIRCLE, manual.shapeValue());
        manual.shape = " square ";
        assertEquals(Sampler.Shape.SQUARE, manual.shapeValue());
    }

    @Test
    void lookupIsCaseInsensitiveAndRespectsDisabledAndAutoWorlds() {
        RtpConfig config = new RtpConfig();
        RtpConfig.WorldSettings off = new RtpConfig.WorldSettings();
        off.enabled = false;
        config.worlds = Map.of("World", new RtpConfig.WorldSettings(), "arena", off);
        assertNotNull(config.settings("world", World.Environment.NORMAL));
        assertNull(config.settings("ARENA", World.Environment.NORMAL));
        assertNotNull(config.settings("mining", World.Environment.NORMAL));
        config.autoWorlds = false;
        assertNull(config.settings("mining", World.Environment.NORMAL));
    }

    @Test
    void environmentDefaults() {
        RtpConfig.WorldSettings end = RtpConfig.WorldSettings.forEnvironment(World.Environment.THE_END);
        assertTrue(end.minRadius > 1000, "skip the void around the main island");
        assertTrue(RtpConfig.WorldSettings.forEnvironment(World.Environment.NORMAL).blockedBiomes.contains("minecraft:ocean"));
        assertEquals("minecraft:plains", RtpSearcher.normaliseBiome(" Plains "));
        assertEquals("terralith:canyon", RtpSearcher.normaliseBiome("terralith:canyon"));
    }
}
