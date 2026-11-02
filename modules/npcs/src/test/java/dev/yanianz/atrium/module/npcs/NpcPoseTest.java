package dev.yanianz.atrium.module.npcs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class NpcPoseTest {
    @Test void aliasesMapToBukkitPoseNames() {
        assertEquals(NpcPose.SNEAKING, NpcPose.parse("crouching").orElseThrow());
        assertEquals(NpcPose.SNEAKING, NpcPose.parse("crouch").orElseThrow());
        assertEquals(NpcPose.SNEAKING, NpcPose.parse("sneak").orElseThrow());
        assertEquals(NpcPose.STANDING, NpcPose.parse("stand").orElseThrow());
        assertEquals(NpcPose.STANDING, NpcPose.parse("default").orElseThrow());
        assertEquals("sitting", NpcPose.parse("sit").orElseThrow());
        assertEquals("sleeping", NpcPose.parse("sleeping").orElseThrow());
        assertEquals("sleeping", NpcPose.parse("lying").orElseThrow());
        assertEquals("swimming", NpcPose.parse("SWIM").orElseThrow());
        assertEquals("spin_attack", NpcPose.parse("spin").orElseThrow());
    }

    @Test void rejectsUnknownAndBlankValues() {
        assertTrue(NpcPose.parse("magic").isEmpty());
        assertTrue(NpcPose.parse("").isEmpty());
        assertTrue(NpcPose.parse("   ").isEmpty());
        assertTrue(NpcPose.parse(null).isEmpty());
    }

    @Test void canonicalOrNormalisesStoredValues() {
        assertEquals(NpcPose.SNEAKING, NpcPose.canonicalOr("Crouching", NpcPose.STANDING));
        assertEquals(NpcPose.STANDING, NpcPose.canonicalOr("nonsense", NpcPose.STANDING));
        assertEquals(NpcPose.STANDING, NpcPose.canonicalOr(null, NpcPose.STANDING));
    }

    @Test void suggestionsAreSortedAndLowercase() {
        assertEquals(List.of("sitting", "sneaking", "standing"),
                NpcPose.suggestions(List.of("Standing", "sneaking", "SITTING")));
    }

    @Test void citizensOnlySupportsStandingAndCrouching() {
        assertEquals(List.of(NpcPose.STANDING, NpcPose.SNEAKING), NpcPose.CITIZENS_POSES);
    }
}
