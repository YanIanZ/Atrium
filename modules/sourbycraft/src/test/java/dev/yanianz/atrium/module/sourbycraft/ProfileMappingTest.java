package dev.yanianz.atrium.module.sourbycraft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.yanianz.atrium.api.profile.ActiveProfileService.ActiveProfile;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mapping on the official 26.2 API (compile baseline); the extended members are covered by ExtendedApiTest. */
class ProfileMappingTest {
    private final UUID owner = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private final UUID other = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private final UUID profileId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private ProfileInfo info(Map<String, String> metadata) {
        return new ProfileInfo(profileId, owner, "skyblock", metadata, 1L, 3L, "ACTIVE", true, List.of("island_x"));
    }

    @Test
    void baselineUsesMetadataNameOwnerRoleAndProfileIdAsVirtualIds() {
        ActiveProfile asOwner = ProfileMapping.toActive(info(Map.of("name", "Mango")), owner, ExtendedApi.NONE);
        assertEquals(profileId, asOwner.id());
        assertEquals("Mango", asOwner.name());
        assertEquals("skyblock", asOwner.type());
        assertEquals("OWNER", asOwner.role());
        assertEquals("ACTIVE", asOwner.status());
        assertEquals(profileId, asOwner.virtualId());
        assertEquals(profileId, asOwner.memberVirtualId());
    }

    @Test
    void baselineFallsBackToTypeAndNoneRole() {
        ActiveProfile profile = ProfileMapping.toActive(info(Map.of("name", " ")), other, ExtendedApi.NONE);
        assertEquals("skyblock", profile.name());
        assertEquals("NONE", profile.role());
    }

    @Test
    void officialApiHasNoExtras() {
        ExtendedApi detected = ExtendedApi.detect(ProfileInfo.class,
                dev.iyanz.sourbycraft.api.profile.AuroraProfiles.class,
                dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter.class,
                ExtendedApi.classLoader(ProfileMappingTest.class.getClassLoader()));
        assertEquals(List.of(), detected.features(), "the compile baseline is the official API");
        assertEquals("Mango", ProfileMapping.toActive(info(Map.of("name", "Mango")), owner, detected).name());
    }
}
