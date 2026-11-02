package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.npcs.NpcType;
import org.junit.jupiter.api.Test;

class NpcEnginesTest {
    @Test void autoPrefersCitizensThenFancyThenNative() {
        assertEquals(NpcType.CITIZENS, NpcEngines.resolve(NpcType.AUTO, true, true));
        assertEquals(NpcType.FANCYNPC, NpcEngines.resolve(NpcType.AUTO, false, true));
        assertEquals(NpcType.NATIVE, NpcEngines.resolve(NpcType.AUTO, false, false));
        assertEquals(NpcType.NATIVE, NpcEngines.resolve(null, false, false));
    }

    @Test void explicitEngineWinsEvenWhenItsPluginIsMissing() {
        assertEquals(NpcType.NATIVE, NpcEngines.resolve(NpcType.NATIVE, true, true));
        assertEquals(NpcType.CITIZENS, NpcEngines.resolve(NpcType.CITIZENS, false, true));
        assertEquals(NpcType.FANCYNPC, NpcEngines.resolve(NpcType.FANCYNPC, true, false));
    }

    @Test void usabilityFollowsThePluginFlags() {
        assertTrue(NpcEngines.usable(NpcType.NATIVE, false, false));
        assertFalse(NpcEngines.usable(NpcType.CITIZENS, false, true));
        assertTrue(NpcEngines.usable(NpcType.CITIZENS, true, false));
        assertFalse(NpcEngines.usable(NpcType.FANCYNPC, true, false));
        assertTrue(NpcEngines.usable(NpcType.FANCYNPC, false, true));
    }

    @Test void parsesAliasesAndRejectsUnknownValues() {
        assertEquals(NpcType.FANCYNPC, NpcType.parse("fancy").orElseThrow());
        assertEquals(NpcType.FANCYNPC, NpcType.parse("fancynpcs").orElseThrow());
        assertEquals(NpcType.NATIVE, NpcType.parse("mannequin").orElseThrow());
        assertEquals(NpcType.CITIZENS, NpcType.parse("CITIZENS").orElseThrow());
        assertTrue(NpcType.parse("magic").isEmpty());
        assertTrue(NpcType.parse(null).isEmpty());
    }
}
