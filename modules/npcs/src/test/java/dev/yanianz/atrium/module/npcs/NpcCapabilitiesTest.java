package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.npcs.NpcType;
import org.junit.jupiter.api.Test;

class NpcCapabilitiesTest {
    @Test void poseIsNativeOnlyWithACitizensPartial() {
        assertEquals(NpcCapabilities.Support.YES, support(NpcType.NATIVE, NpcCapabilities.Capability.POSE));
        assertEquals(NpcCapabilities.Support.PARTIAL, support(NpcType.CITIZENS, NpcCapabilities.Capability.POSE));
        assertEquals(NpcCapabilities.Support.NO, support(NpcType.FANCYNPC, NpcCapabilities.Capability.POSE));
    }

    @Test void glowColourOnlyFullyWorksOnFancyNpcs() {
        assertEquals(NpcCapabilities.Support.PARTIAL, support(NpcType.NATIVE, NpcCapabilities.Capability.GLOW_COLOUR));
        assertEquals(NpcCapabilities.Support.PARTIAL, support(NpcType.CITIZENS, NpcCapabilities.Capability.GLOW_COLOUR));
        assertEquals(NpcCapabilities.Support.YES, support(NpcType.FANCYNPC, NpcCapabilities.Capability.GLOW_COLOUR));
    }

    @Test void sharedFeaturesWorkEverywhere() {
        for (NpcCapabilities.Capability capability : new NpcCapabilities.Capability[] {
                NpcCapabilities.Capability.SCALE, NpcCapabilities.Capability.NAMEPLATE,
                NpcCapabilities.Capability.SKIN, NpcCapabilities.Capability.EQUIPMENT,
                NpcCapabilities.Capability.LOOK_AT, NpcCapabilities.Capability.ACTIONS}) {
            for (NpcType type : new NpcType[] {NpcType.NATIVE, NpcType.CITIZENS, NpcType.FANCYNPC}) {
                assertEquals(NpcCapabilities.Support.YES, support(type, capability),
                        capability + " on " + type.configName());
            }
        }
    }

    @Test void packetOnlyFancyNpcsCannotBeHitByAnEntityRay() {
        assertEquals(NpcCapabilities.Support.YES, support(NpcType.NATIVE, NpcCapabilities.Capability.EXACT_SELECT));
        assertEquals(NpcCapabilities.Support.YES, support(NpcType.CITIZENS, NpcCapabilities.Capability.EXACT_SELECT));
        assertEquals(NpcCapabilities.Support.PARTIAL, support(NpcType.FANCYNPC, NpcCapabilities.Capability.EXACT_SELECT));
    }

    @Test void autoUsesTheNativeMatrix() {
        assertEquals(support(NpcType.NATIVE, NpcCapabilities.Capability.POSE),
                support(NpcType.AUTO, NpcCapabilities.Capability.POSE));
        assertEquals(support(NpcType.NATIVE, NpcCapabilities.Capability.GLOW_COLOUR),
                support(NpcType.AUTO, NpcCapabilities.Capability.GLOW_COLOUR));
    }

    @Test void everyCellHasAPresentableLabel() {
        assertFalse(NpcCapabilities.all().isEmpty());
        for (NpcCapabilities.Capability capability : NpcCapabilities.all()) {
            assertNotNull(capability.messageKey());
            assertTrue(capability.messageKey().startsWith("npcs."), capability.messageKey());
        }
        for (NpcCapabilities.Support support : NpcCapabilities.Support.values()) {
            assertTrue(support.messageKey().startsWith("npcs."), support.messageKey());
        }
    }

    private static NpcCapabilities.Support support(NpcType type, NpcCapabilities.Capability capability) {
        return NpcCapabilities.support(type, capability);
    }
}
