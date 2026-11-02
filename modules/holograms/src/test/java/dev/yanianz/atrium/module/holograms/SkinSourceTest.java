package dev.yanianz.atrium.module.holograms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The only accepted skin source is the Mojang texture host over HTTPS. */
class SkinSourceTest {
    private static final String GOOD = "https://textures.minecraft.net/texture/abc123";

    @Test
    void onlyTheMojangTextureHostOverHttpsIsAllowed() {
        assertTrue(SkinSource.allowedTextureUrl(GOOD));
        assertTrue(SkinSource.allowedTextureUrl("https://TEXTURES.minecraft.net/texture/abc"));
        assertTrue(SkinSource.allowedTextureUrl("https://textures.minecraft.net:443/texture/abc"));
        assertFalse(SkinSource.allowedTextureUrl("http://textures.minecraft.net/texture/abc"));
        assertFalse(SkinSource.allowedTextureUrl("https://textures.minecraft.net.evil.example/texture/abc"));
        assertFalse(SkinSource.allowedTextureUrl("https://example.com/texture/abc"));
        assertFalse(SkinSource.allowedTextureUrl("https://user@textures.minecraft.net/texture/abc"));
        assertFalse(SkinSource.allowedTextureUrl("https://textures.minecraft.net:8443/texture/abc"));
        assertFalse(SkinSource.allowedTextureUrl("https://textures.minecraft.net"));
        assertFalse(SkinSource.allowedTextureUrl(null));
        assertFalse(SkinSource.allowedTextureUrl("not a url"));
    }

    @Test
    void theSkinUrlIsReadFromTheSignedTexturesProperty() {
        String json = "{\"timestamp\":1,\"profileName\":\"Alex\",\"textures\":{\"SKIN\":{\"url\":\""
                + GOOD + "\"}}}";
        assertEquals(Optional.of(GOOD), SkinSource.textureUrl(base64(json)));
    }

    @Test
    void aPropertyPointingAnywhereElseIsIgnored() {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"https://evil.example/skin.png\"}}}";
        assertTrue(SkinSource.textureUrl(base64(json)).isEmpty());
        assertTrue(SkinSource.textureUrl("not base64").isEmpty());
        assertTrue(SkinSource.textureUrl(null).isEmpty());
        assertTrue(SkinSource.textureUrl(base64("{\"textures\":{}}")).isEmpty());
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
