package dev.yanianz.atrium.module.holograms;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineNamesTest {
    @Test void prefixesAndLowerCasesStableNames() {
        assertEquals("atrium_spawn", EngineNames.externalName("Spawn"));
        assertEquals("atrium_spawn", EngineNames.externalName("spawn"));
        assertEquals("atrium_my_holo", EngineNames.externalName("My_Holo"));
    }

    @Test void replacesCharactersTheExternalPluginRejects() {
        assertEquals("atrium_a_b_c", EngineNames.externalName("a.b c"));
        assertEquals("atrium_ab_cd", EngineNames.externalName("ab#cd"));
        assertEquals("atrium_hologram", EngineNames.externalName("   "));
    }

    @Test void keepsTheExternalNameWithinThePluginLimit() {
        String external = EngineNames.externalName("x".repeat(200));
        assertEquals(64, external.length());
        assertTrue(EngineNames.isManaged(external));
    }

    @Test void roundTripsManagedNamesAndIgnoresForeignOnes() {
        assertEquals(Optional.of("spawn"), EngineNames.atriumName(EngineNames.externalName("Spawn")));
        assertEquals(Optional.empty(), EngineNames.atriumName("shop"));
        assertEquals(Optional.empty(), EngineNames.atriumName("atrium_"));
        assertEquals(Optional.empty(), EngineNames.atriumName(null));
        assertTrue(EngineNames.isManaged("atrium_shop"));
        assertFalse(EngineNames.isManaged("atrium_shop/other"));
        assertFalse(EngineNames.isManaged("other_atrium_shop"));
    }

    @Test void validatesBeforeHandingANameToAPlugin() {
        assertTrue(EngineNames.validExternalName("atrium_ok-1"));
        assertFalse(EngineNames.validExternalName("atrium bad"));
        assertFalse(EngineNames.validExternalName(""));
    }
}
