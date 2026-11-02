package dev.yanianz.atrium.module.placeholders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.yanianz.atrium.api.profile.ActiveProfileService.ActiveProfile;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProfilePlaceholdersTest {
    private static final ActiveProfile PROFILE = new ActiveProfile(UUID.randomUUID(), "Mango", "skyblock", "OWNER",
            "ACTIVE", UUID.randomUUID(), UUID.randomUUID());

    @Test
    void handlesOnlyProfileKeys() {
        assertTrue(ProfilePlaceholders.handles("profile"));
        assertTrue(ProfilePlaceholders.handles("profile_role"));
        assertFalse(ProfilePlaceholders.handles("balance"));
    }

    @Test
    void resolvesFromCacheOrFallback() {
        assertEquals("Mango", ProfilePlaceholders.resolve("profile", Optional.of(PROFILE), "-"));
        assertEquals("skyblock", ProfilePlaceholders.resolve("profile_type", Optional.of(PROFILE), "-"));
        assertEquals("OWNER", ProfilePlaceholders.resolve("profile_role", Optional.of(PROFILE), "-"));
        assertEquals("-", ProfilePlaceholders.resolve("profile", Optional.empty(), "-"));
    }

    @Test
    void sanitisesProfileText() {
        ActiveProfile odd = new ActiveProfile(UUID.randomUUID(), "&cRed%x%", "t", "MEMBER", "ACTIVE",
                UUID.randomUUID(), UUID.randomUUID());
        String value = ProfilePlaceholders.resolve("profile", Optional.of(odd), "");
        assertFalse(value.contains("&c"));
        assertFalse(value.contains("%"));
    }
}
