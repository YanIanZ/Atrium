package dev.yanianz.atrium.module.holograms;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where a skin image may come from. Only the texture URL inside a Mojang-signed profile value is accepted,
 * and only when it points at {@code https://textures.minecraft.net/...}; no other host is ever contacted.
 */
final class SkinSource {
    static final String TEXTURE_HOST = "textures.minecraft.net";
    private static final Pattern URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SKIN = Pattern.compile("\"SKIN\"\\s*:");

    private SkinSource() {}

    /** True for exactly {@code https://textures.minecraft.net/<path>} with no user info and no other port. */
    static boolean allowedTextureUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equals(uri.getScheme())) {
            return false;
        }
        if (uri.getUserInfo() != null) {
            return false;
        }
        if (!TEXTURE_HOST.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            return false;
        }
        return uri.getPath() != null && !uri.getPath().isBlank();
    }

    /** The skin URL of a base64 {@code textures} profile property, if it has one. */
    static Optional<String> textureUrl(String base64) {
        if (base64 == null || base64.isBlank() || base64.length() > 32_768) {
            return Optional.empty();
        }
        String json;
        try {
            json = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Matcher skin = SKIN.matcher(json);
        if (!skin.find()) {
            return Optional.empty();
        }
        Matcher url = URL.matcher(json);
        if (!url.find(skin.end())) {
            return Optional.empty();
        }
        String value = url.group(1);
        return allowedTextureUrl(value) ? Optional.of(value) : Optional.empty();
    }

    /** Normalised cache key for a player name. */
    static String cacheKey(String player) {
        return player.toLowerCase(Locale.ROOT);
    }
}
