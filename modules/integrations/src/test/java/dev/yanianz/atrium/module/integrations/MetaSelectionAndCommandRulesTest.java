package dev.yanianz.atrium.module.integrations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MetaSelectionAndCommandRulesTest {
    @Test
    void autoPrefersLuckPermsThenVaultThenEmpty() {
        assertEquals(IntegrationsConfig.MetaBackend.LUCKPERMS,
                MetaSelection.choose(IntegrationsConfig.MetaBackend.AUTO, true, true));
        assertEquals(IntegrationsConfig.MetaBackend.VAULT,
                MetaSelection.choose(IntegrationsConfig.MetaBackend.AUTO, false, true));
        assertEquals(IntegrationsConfig.MetaBackend.NONE,
                MetaSelection.choose(IntegrationsConfig.MetaBackend.AUTO, false, false));
    }

    @Test
    void explicitProviderDoesNotSilentlyUseAnotherOne() {
        assertEquals(IntegrationsConfig.MetaBackend.NONE,
                MetaSelection.choose(IntegrationsConfig.MetaBackend.LUCKPERMS, false, true));
        assertEquals(IntegrationsConfig.MetaBackend.LUCKPERMS,
                MetaSelection.choose(IntegrationsConfig.MetaBackend.LUCKPERMS, true, false));
        assertEquals(IntegrationsConfig.MetaBackend.NONE,
                MetaSelection.choose(IntegrationsConfig.MetaBackend.NONE, true, true));
    }

    @Test
    void worldGuardCommandFlagsAreHonoured() {
        assertTrue(CommandRules.allowed("/sethome", Set.of(), Set.of()));
        assertFalse(CommandRules.allowed("sethome", Set.of("sethome"), Set.of()));
        assertFalse(CommandRules.allowed("sethome", Set.of("*"), Set.of()));
        assertTrue(CommandRules.allowed("sethome", Set.of("home*"), Set.of()));
        assertFalse(CommandRules.allowed("sethome", Set.of(), Set.of("home", "spawn")));
        assertTrue(CommandRules.allowed("spawn", Set.of("sethome"), Set.of("home", "spawn")));
        assertFalse(CommandRules.allowed("sethome extra", Set.of("sethome"), Set.of()));
    }
}
