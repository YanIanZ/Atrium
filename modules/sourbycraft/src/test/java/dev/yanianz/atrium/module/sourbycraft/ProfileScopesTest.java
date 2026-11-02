package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProfileScopesTest {
    @Test
    void defaultsParse() {
        List<String> warnings = new ArrayList<>();
        assertEquals(List.of("homes", "back", "kits"),
                ProfileScopes.parse(new SourbyCraftConfig().profileScoped, warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void normalisesDeduplicatesAndRejects() {
        List<String> warnings = new ArrayList<>();
        List<String> parsed = ProfileScopes.parse(Arrays.asList(" Homes ", "homes", "economy", "nick", "bad id!", "", null,
                "kits"), warnings::add);
        assertEquals(List.of("homes", "kits"), parsed);
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("purse")), "economy points to the purse");
    }

    @Test
    void nullListIsEmpty() {
        assertEquals(List.of(), ProfileScopes.parse(null, w -> {}));
    }

    @Test
    void missingState() {
        List<String> warnings = new ArrayList<>();
        assertEquals(ProfileScopes.MissingState.KEEP, ProfileScopes.missingState(" keep ", warnings::add));
        assertEquals(ProfileScopes.MissingState.EMPTY, ProfileScopes.missingState("EMPTY", warnings::add));
        assertTrue(warnings.isEmpty());
        assertEquals(ProfileScopes.MissingState.EMPTY, ProfileScopes.missingState("wipe", warnings::add));
        assertEquals(1, warnings.size());
    }
}
