package dev.yanianz.atrium.module.particles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.module.particles.shape.Style;
import java.util.List;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.junit.jupiter.api.Test;

class ParticleProfileTest {
    private static ParticleEffect effect(Style style, Particle particle) {
        return new ParticleEffect(style, particle,
                new ParticleColours(ParticleColours.Mode.FIXED, Color.RED, Color.BLUE), 1.0, 1.0, 4.0);
    }

    @Test
    void addingADifferentStyleUsesTheNextSlot() {
        ParticleProfile profile = ParticleProfile.EMPTY;
        ParticleProfile.AddResult first = ParticleProfile.add(profile, effect(Style.HALO, Particle.DUST), 2);
        assertFalse(first.full());
        assertFalse(first.replaced());
        assertEquals(1, first.profile().effects().size());

        ParticleProfile.AddResult second = ParticleProfile.add(first.profile(),
                effect(Style.WINGS, Particle.FLAME), 2);
        assertFalse(second.full());
        assertEquals(2, second.profile().effects().size());
    }

    @Test
    void sameStyleReplacesWithoutUsingASlot() {
        ParticleProfile profile = ParticleProfile.EMPTY;
        profile = ParticleProfile.add(profile, effect(Style.HALO, Particle.DUST), 1).profile();
        ParticleProfile.AddResult replaced = ParticleProfile.add(profile,
                effect(Style.HALO, Particle.FLAME), 1);
        assertFalse(replaced.full());
        assertTrue(replaced.replaced());
        assertEquals(1, replaced.profile().effects().size());
        assertEquals(Particle.FLAME, replaced.profile().effect(Style.HALO).particle());
    }

    @Test
    void fullProfileRefusesANewStyle() {
        ParticleProfile profile = ParticleProfile.add(ParticleProfile.EMPTY,
                effect(Style.HALO, Particle.DUST), 1).profile();
        ParticleProfile.AddResult result = ParticleProfile.add(profile, effect(Style.WINGS, Particle.FLAME), 1);
        assertTrue(result.full());
        assertEquals(1, result.profile().effects().size());
        assertNull(result.profile().effect(Style.WINGS));
    }

    @Test
    void removingAndClearingKeepTheVisibilityFlags() {
        ParticleProfile profile = new ParticleProfile(
                List.of(effect(Style.HALO, Particle.DUST), effect(Style.WINGS, Particle.FLAME)), true, true);
        ParticleProfile removed = profile.without(Style.HALO);
        assertEquals(1, removed.effects().size());
        assertTrue(removed.hideOthers());
        assertTrue(removed.hidden());

        ParticleProfile cleared = removed.withoutAll();
        assertTrue(cleared.effects().isEmpty());
        assertTrue(cleared.hideOthers());
        assertTrue(cleared.hidden());
    }

    @Test
    void flagsCanBeToggledIndependently() {
        ParticleProfile profile = ParticleProfile.EMPTY.withHideOthers(true).withHidden(true);
        assertTrue(profile.hideOthers());
        assertTrue(profile.hidden());
        assertFalse(profile.withHideOthers(false).hideOthers());
        assertTrue(profile.withHideOthers(false).hidden());
    }

    @Test
    void limitKeepsTheFirstEffectsAndTheVisibilityFlags() {
        ParticleProfile profile = new ParticleProfile(
                List.of(effect(Style.HALO, Particle.DUST), effect(Style.WINGS, Particle.FLAME),
                        effect(Style.ORBIT, Particle.FLAME)),
                true, true);

        ParticleProfile limited = profile.limit(2);
        assertEquals(2, limited.effects().size());
        assertEquals(Style.HALO, limited.effects().get(0).style());
        assertEquals(Style.WINGS, limited.effects().get(1).style());
        assertTrue(limited.hideOthers());
        assertTrue(limited.hidden());

        assertSame(profile, profile.limit(3), "no trimming needed");
        assertSame(profile, profile.limit(99), "limit above the size keeps the profile");
        assertTrue(profile.limit(0).effects().isEmpty(), "a lost slot hides every effect");
        assertTrue(profile.limit(-1).effects().isEmpty(), "negative limit is treated as zero");
        assertTrue(profile.withoutAll().limit(5).effects().isEmpty());
    }
}
