package dev.yanianz.atrium.module.particles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

class ParticleNamesTest {
    @Test
    void acceptsEnumNamesAndKeys() {
        assertEquals(Particle.DUST, ParticleNames.resolve("dust"));
        assertEquals(Particle.DUST, ParticleNames.resolve("DUST"));
        assertEquals(Particle.DUST_COLOR_TRANSITION, ParticleNames.resolve("dust-color-transition"));
        assertEquals(Particle.END_ROD, ParticleNames.resolve("end_rod"));
        assertNull(ParticleNames.resolve("not_a_particle"));
        assertNull(ParticleNames.resolve(null));
    }

    @Test
    void keyAndPermissionUseTheNamespacedKey() {
        assertEquals("dust_color_transition", ParticleNames.key(Particle.DUST_COLOR_TRANSITION));
        assertEquals("atrium.particles.particle.dust", ParticleNames.permission(Particle.DUST));
    }
}
