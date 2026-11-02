package dev.yanianz.atrium.module.worldrules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResurrectionEffectsTest {
    @Test
    void parsesNameSecondsAndAmplifier() {
        ResurrectionEffects.Spec spec = ResurrectionEffects.parse("regeneration:45:1");
        assertNotNull(spec);
        assertEquals("REGENERATION", spec.name());
        assertEquals(45, spec.seconds());
        assertEquals(1, spec.amplifier());
    }

    @Test
    void amplifierIsOptional() {
        ResurrectionEffects.Spec spec = ResurrectionEffects.parse("FIRE_RESISTANCE:40");
        assertNotNull(spec);
        assertEquals(0, spec.amplifier());
    }

    @Test
    void malformedLinesAreRejected() {
        assertNull(ResurrectionEffects.parse("REGENERATION"));
        assertNull(ResurrectionEffects.parse(":10:1"));
        assertNull(ResurrectionEffects.parse("REGENERATION:abc"));
        assertNull(ResurrectionEffects.parse("REGENERATION:0:1"));
        assertNull(ResurrectionEffects.parse("REGENERATION:10:-1"));
        assertNull(ResurrectionEffects.parse("REGENERATION:10:300"));
        assertNull(ResurrectionEffects.parse("REGENERATION:10:1:2"));
        assertNull(ResurrectionEffects.parse(""));
    }

    @Test
    void parseAllSkipsInvalidLines() {
        List<ResurrectionEffects.Spec> specs =
                ResurrectionEffects.parseAll(List.of("REGENERATION:45:1", "junk", "ABSORPTION:5:0"));
        assertEquals(2, specs.size());
        assertEquals("ABSORPTION", specs.get(1).name());
        assertTrue(ResurrectionEffects.parseAll(null).isEmpty());
    }
}
