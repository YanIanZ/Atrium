package dev.yanianz.atrium.module.particles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

class ParticleDataTest {
    @Test
    void supportedDataTypesCoverEveryColourAndDataKindWeBuild() {
        assertTrue(ParticleData.supported(Particle.DUST));
        assertTrue(ParticleData.supported(Particle.DUST_COLOR_TRANSITION));
        assertTrue(ParticleData.supported(Particle.ENTITY_EFFECT));
        assertTrue(ParticleData.supported(Particle.ITEM));
        assertTrue(ParticleData.supported(Particle.BLOCK));
        assertTrue(ParticleData.supported(Particle.FALLING_DUST));
        assertTrue(ParticleData.supported(Particle.FLAME));
    }

    @Test
    void particlesNeedingComplexDataAreRejectedBeforeSpawning() {
        assertFalse(ParticleData.supported(Particle.VIBRATION));
    }
}
